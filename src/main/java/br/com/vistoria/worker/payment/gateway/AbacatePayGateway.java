package br.com.vistoria.worker.payment.gateway;

import br.com.vistoria.core.gateway.HttpGateway;
import br.com.vistoria.core.gateway.HttpGatewayRequest;
import br.com.vistoria.core.gateway.HttpGatewayResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Provider real de pagamento (docs.abacatepay.com, API v2). Fluxo: produto dinamico +
 * link de pagamento reutilizavel (POST /products/create -> POST /payment-links/create),
 * porque devolve uma URL de checkout de verdade (data.url) para o link mandado por email
 * — o fluxo "transparent" (valor direto) so devolve QR code Pix ou linha de boleto, sem
 * URL generica de checkout. Cria 1 produto novo por cobranca (efeito colateral aceito:
 * nao ha catalogo real de servicos).
 *
 * <p>{@code /v2/payment-links/create} ainda exige um produto pre-criado — {@code items[].id}
 * e "ID publico do produto na sua loja" (confirmado no OpenAPI oficial da AbacatePay); nao
 * aceita item inline com nome/preco.</p>
 *
 * <p>Autenticidade do webhook: validada uma unica vez, na borda ({@code inspecta-messenger},
 * {@code ?webhookSecret=}). Este gateway so faz parsing do payload — nao reprocessa
 * assinatura (a AbacatePay tambem oferece HMAC via {@code X-Webhook-Signature}, mas
 * validar as duas camadas seria redundante com a barreira do inspecta-messenger).</p>
 */
@Component("abacatepay")
public class AbacatePayGateway extends AbstractHttpPaymentGateway {

    private static final String BASE_URL = "https://api.abacatepay.com/v2";

    // Payment links sao "checkouts com frequency MULTIPLE_PAYMENTS" na API da AbacatePay —
    // compartilham os mesmos eventos de webhook dos checkouts comuns (confirmado no OpenAPI
    // oficial: enum de WebhookCreateInput.events).
    private static final List<String> EVENTOS_PAGO = List.of("checkout.completed", "transparent.completed");
    private static final List<String> EVENTOS_ESTORNADO = List.of("checkout.refunded", "transparent.refunded");

    private static final Set<String> METODOS_VALIDOS = Set.of("PIX", "CARD", "BOLETO");

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${inspecta.payment.abacatepay.api-key:}") private String apiKey;
    @Value("${inspecta.payment.methods:PIX,CARD}") private List<String> paymentMethods;

    public AbacatePayGateway(HttpGateway httpGateway) {
        super(httpGateway);
    }

    @PostConstruct
    void validarMetodosPagamento() {
        if (paymentMethods == null || paymentMethods.isEmpty()) {
            throw new IllegalStateException("inspecta.payment.methods nao pode ser vazio");
        }
        for (String metodo : paymentMethods) {
            String normalizado = metodo == null ? "" : metodo.trim().toUpperCase(Locale.ROOT);
            if (!METODOS_VALIDOS.contains(normalizado)) {
                throw new IllegalStateException(
                        "inspecta.payment.methods contem valor invalido: '" + metodo + "'. "
                                + "Valores aceitos pela AbacatePay: " + METODOS_VALIDOS);
            }
        }
    }

    public boolean configurado() { return apiKey != null && !apiKey.isBlank(); }

    @Override public String nome() { return "abacatepay"; }

    @Override
    public ChargeResult createCharge(BigDecimal valor, Map<String, Object> metadata) {
        try {
            String externalId = "cobranca-" + metadata.getOrDefault("solicitacaoId", System.currentTimeMillis());
            long precoCentavos = valor.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();

            String produtoBody = mapper.writeValueAsString(Map.of(
                    "externalId", externalId,
                    "name", "Servico Inspecta",
                    "price", precoCentavos,
                    "currency", "BRL"));
            JsonNode produto = extrairDataOuFalhar(post("/products/create", produtoBody), "criar produto");
            String produtoId = produto.path("id").asText();

            String paymentLinkBody = mapper.writeValueAsString(Map.of(
                    "frequency", "MULTIPLE_PAYMENTS",
                    "items", List.of(Map.of("id", produtoId, "quantity", 1)),
                    "methods", paymentMethods,
                    "externalId", externalId,
                    "metadata", metadata));
            JsonNode paymentLink = extrairDataOuFalhar(post("/payment-links/create", paymentLinkBody), "criar link de pagamento");

            return new ChargeResult(paymentLink.path("id").asText(), paymentLink.path("url").asText());
        } catch (Exception e) {
            throw new IllegalStateException("abacatepay createCharge falhou", e);
        }
    }

    @Override
    public ProductResult createProduct(ProductRequest request) {
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "externalId", request.externalId(),
                    "name", request.name(),
                    "price", request.priceCents(),
                    "currency", request.currency() == null ? "BRL" : request.currency()));
            JsonNode data = extrairDataOuFalhar(post("/products/create", body), "criar produto");
            return new ProductResult(data.path("id").asText(), request.externalId());
        } catch (Exception e) {
            throw new IllegalStateException("abacatepay createProduct falhou", e);
        }
    }

    @Override
    public CheckoutResult createCheckout(CheckoutRequest request) {
        try {
            List<Map<String, Object>> items = request.productIds().stream()
                    .map(id -> Map.<String, Object>of("id", id, "quantity", 1))
                    .toList();
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("items", items);
            body.put("methods", request.methods() == null || request.methods().isEmpty() ? paymentMethods : request.methods());
            body.put("externalId", request.externalId());
            if (request.customerId() != null && !request.customerId().isBlank()) body.put("customerId", request.customerId());
            if (request.returnUrl() != null) body.put("returnUrl", request.returnUrl());
            if (request.completionUrl() != null) body.put("completionUrl", request.completionUrl());
            JsonNode data = extrairDataOuFalhar(post("/checkouts/create", mapper.writeValueAsString(body)), "criar checkout");
            return new CheckoutResult(data.path("id").asText(), data.path("url").asText());
        } catch (Exception e) {
            throw new IllegalStateException("abacatepay createCheckout falhou", e);
        }
    }

    @Override
    public CustomerResult createCustomer(CustomerRequest request) {
        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("email", request.email());
            if (request.name() != null && !request.name().isBlank()) body.put("name", request.name());
            if (request.taxId() != null && !request.taxId().isBlank()) body.put("taxId", request.taxId());
            if (request.cellphone() != null && !request.cellphone().isBlank()) body.put("cellphone", request.cellphone());
            if (request.zipCode() != null && !request.zipCode().isBlank()) body.put("zipCode", request.zipCode());
            JsonNode data = extrairDataOuFalhar(post("/customers/create", mapper.writeValueAsString(body)), "criar customer");
            return new CustomerResult(data.path("id").asText());
        } catch (Exception e) {
            throw new IllegalStateException("abacatepay createCustomer falhou", e);
        }
    }

    @Override
    public void deleteCustomer(String customerId) {
        try {
            extrairDataOuFalhar(post("/customers/delete", mapper.writeValueAsString(Map.of("id", customerId))), "remover customer");
        } catch (Exception e) {
            throw new IllegalStateException("abacatepay deleteCustomer falhou", e);
        }
    }

    @Override
    public String getStatus(String externalId) {
        try {
            HttpGatewayResponse res = chamar(HttpGatewayRequest.get(
                    URI.create(BASE_URL + "/payment-links/get?id=" + externalId), headers()));
            return extrairDataOuFalhar(res, "consultar status").path("status").asText();
        } catch (Exception e) {
            throw new IllegalStateException("abacatepay getStatus falhou", e);
        }
    }

    @Override
    public void refund(String externalId) {
        try {
            String body = mapper.writeValueAsString(Map.of("id", externalId));
            extrairDataOuFalhar(post("/payment-links/refund", body), "estornar");
        } catch (Exception e) {
            throw new IllegalStateException("abacatepay refund falhou", e);
        }
    }

    @Override
    public PaymentEvent parseWebhook(String payload, Map<String, String> headers) {
        try {
            JsonNode root = mapper.readTree(payload);
            String evento = root.path("event").asText();
            String eventId = root.path("id").asText();
            String externalId = externalIdDoEvento(evento, root.path("data"));
            String status = mapearStatus(evento);
            return new PaymentEvent(eventId, externalId, status);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("payload de webhook abacatepay invalido", e);
        }
    }

    /**
     * Id usado para casar o webhook com o nosso registro. Checkout: {@code data.id}. PIX transparente:
     * a doc do AbacatePay não publica o formato do payload de {@code transparent.*}; aceitamos
     * {@code data.transparent.id} ou {@code data.id} (o que vier preenchido). A reconciliação
     * periódica ({@code PixReconciliacaoCron}) cobre qualquer divergência.
     */
    private static String externalIdDoEvento(String evento, JsonNode data) {
        if (evento != null && evento.startsWith("transparent.")) {
            String aninhado = data.path("transparent").path("id").asText("");
            if (!aninhado.isBlank()) return aninhado;
        }
        return data.path("id").asText();
    }

    @Override
    public PixResult createPix(PixRequest request) {
        try {
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("amount", request.amountCents());
            data.put("expiresIn", request.expiresInSeconds());
            if (request.description() != null) data.put("description", request.description());
            if (request.metadata() != null && !request.metadata().isEmpty()) data.put("metadata", request.metadata());
            CustomerRequest c = request.customer();
            if (c != null) {
                data.put("customer", Map.of("name", c.name(), "email", c.email(),
                        "taxId", c.taxId(), "cellphone", c.cellphone()));
            }
            String body = mapper.writeValueAsString(Map.of("method", "PIX", "data", data));
            JsonNode d = extrairDataOuFalhar(post("/transparents/create", body), "criar PIX");
            String expires = d.path("expiresAt").asText("");
            java.time.Instant expiresAt = expires.isBlank()
                    ? java.time.Instant.now().plusSeconds(request.expiresInSeconds())
                    : java.time.Instant.parse(expires);
            return new PixResult(d.path("id").asText(), d.path("brCode").asText(),
                    d.path("brCodeBase64").asText(), expiresAt);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("abacatepay createPix falhou", e);
        }
    }

    @Override
    public String pixStatus(String pixId) {
        try {
            HttpGatewayResponse res = chamar(HttpGatewayRequest.get(
                    URI.create(BASE_URL + "/transparents/check?id=" + pixId), headers()));
            String st = extrairDataOuFalhar(res, "consultar PIX").path("status").asText("");
            return switch (st.toUpperCase()) {
                case "PAID" -> "pago";
                case "EXPIRED", "CANCELLED" -> "expirado";
                default -> "pendente";
            };
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("abacatepay pixStatus falhou", e);
        }
    }

    private String mapearStatus(String evento) {
        if (EVENTOS_PAGO.contains(evento)) return "pago";
        if (EVENTOS_ESTORNADO.contains(evento)) return "estornado";
        throw new IllegalArgumentException("evento de webhook abacatepay nao suportado: " + evento);
    }

    private HttpGatewayResponse post(String path, String body) throws Exception {
        return chamar(HttpGatewayRequest.post(URI.create(BASE_URL + path), headers(), body));
    }

    private JsonNode extrairDataOuFalhar(HttpGatewayResponse res, String contexto) throws Exception {
        if (!res.sucesso()) throw new IllegalStateException("abacatepay " + contexto + " HTTP " + res.status() + ": " + res.body());
        JsonNode root = mapper.readTree(res.body().isBlank() ? "{}" : res.body());
        if (!root.path("success").asBoolean(false)) {
            throw new IllegalStateException("abacatepay " + contexto + ": " + root.path("error").asText("erro desconhecido"));
        }
        return root.path("data");
    }

    private Map<String, String> headers() {
        return Map.of("Authorization", "Bearer " + apiKey, "Content-Type", "application/json");
    }
}
