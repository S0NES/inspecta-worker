package br.com.vistoria.worker.payment;

import br.com.vistoria.core.events.EventTypes;
import br.com.vistoria.core.modules.financeiro.CheckoutService;
import br.com.vistoria.core.modules.financeiro.PagamentoService;
import br.com.vistoria.core.modules.imobiliaria.ImobiliariaService;
import br.com.vistoria.core.modules.notify.NotifyTemplates;
import br.com.vistoria.core.shared.outbox.OutboxService;
import br.com.vistoria.worker.payment.gateway.PaymentGateway;
import br.com.vistoria.worker.shared.Eventos;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * q.payment (routing {@code jobs.payment.#}). Todo o I/O com o provider de pagamento vive aqui,
 * fora da thread de request do inspecta-api:
 *
 * <ul>
 *   <li>{@code charge-requested} -> cria 1 produto por serviço + 1 checkout, grava ids, publica
 *       {@code checkout-ready} e manda o e-mail com o link. Falha de provider -> {@code payment_status='erro'}
 *       e o retrigger manual ({@code POST /v1/solicitacoes/{id}/pagamento}) continua funcionando.</li>
 *   <li>{@code webhook-received} -> parse + {@code PagamentoService.aplicarEvento} (que, na aresta
 *       {@code pago}, publica as atribuições).</li>
 *   <li>{@code customer-create} / {@code customer-delete} -> ciclo de vida do customer no provider.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentConsumer {

    private final Map<String, PaymentGateway> gateways;
    private final PagamentoService pagamentoService;
    private final CheckoutService checkoutService;
    private final ImobiliariaService imobiliariaService;
    private final OutboxService outbox;

    @Value("${inspecta.payment.provider:mock}")
    private String providerAtivo;

    @Value("${inspecta.payment.methods:PIX,CARD}")
    private String methods;

    /** Base do SPA (inspecta-web) para as URLs de retorno/conclusão do checkout. */
    @Value("${inspecta.app-url:http://localhost:5173}")
    private String appUrl;

    /** Validade do PIX transparente, em segundos (padrão 1 h). */
    @Value("${inspecta.payment.pix-expires-seconds:3600}")
    private int pixExpiresSeconds;

    @RabbitListener(queues = "q.payment", containerFactory = "paymentListenerFactory")
    @Transactional
    public void on(Message message) {
        JsonNode e = Eventos.parse(message.getBody());
        String type = message.getMessageProperties().getReceivedRoutingKey();
        switch (type) {
            case EventTypes.PAYMENT_CHARGE_REQUESTED -> prepararCheckout(e);
            case EventTypes.PAYMENT_WEBHOOK -> processarWebhook(e);
            case EventTypes.PAYMENT_PIX_REQUESTED -> gerarPix(e);
            // jobs.payment.# também entrega o evento da fatura aqui; quem trata é o FaturaCheckoutConsumer.
            case EventTypes.PAYMENT_FATURA_CHARGE_REQUESTED -> { }
            case EventTypes.PAYMENT_CUSTOMER_CREATE -> criarCustomer(e);
            case EventTypes.PAYMENT_CUSTOMER_DELETE -> removerCustomer(e);
            case EventTypes.PAYMENT_CHECKOUT_READY -> { /* evento de domínio informativo — nada a fazer aqui */ }
            default -> log.warn("[payment] routing key inesperada {}", type);
        }
    }

    /** Gera o PIX transparente e grava QR/copia-e-cola no pagamento. Falha não relança (evita retry-storm): a UI re-solicita. */
    private void gerarPix(JsonNode e) {
        UUID solicitacaoId = UUID.fromString(e.path("solicitacaoId").asText());
        UUID pagamentoId = UUID.fromString(e.path("pagamentoId").asText());
        PaymentGateway gateway = resolver(providerAtivo);
        try {
            long cents = new java.math.BigDecimal(e.path("valor").asText())
                    .movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
            var pagador = pagamentoService.pagadorDe(solicitacaoId);
            var customer = pagador == null ? null : new PaymentGateway.CustomerRequest(
                    pagador.email(), pagador.nome(), pagador.cpf(), pagador.telefone(), null);
            var pix = gateway.createPix(new PaymentGateway.PixRequest(cents, pixExpiresSeconds,
                    "Vistoria Inspecta - solicitacao " + solicitacaoId,
                    java.util.Map.of("solicitacaoId", solicitacaoId.toString(), "pagamentoId", pagamentoId.toString()),
                    customer));
            pagamentoService.registrarPix(pagamentoId, gateway.nome(), pix.id(), pix.brCode(), pix.qrBase64(), pix.expiresAt());
            log.info("[payment] PIX pronto solicitacao={} provider={} pixId={}", solicitacaoId, gateway.nome(), pix.id());
        } catch (RuntimeException ex) {
            log.error("[payment] falha ao gerar PIX solicitacao={}", solicitacaoId, ex);
        }
    }

    private void prepararCheckout(JsonNode e) {
        UUID solicitacaoId = UUID.fromString(e.path("solicitacaoId").asText());
        UUID pagamentoId = UUID.fromString(e.path("pagamentoId").asText());
        PaymentGateway gateway = resolver(providerAtivo);
        try {
            CheckoutService.PlanoCheckout plano = checkoutService.planejar(solicitacaoId);

            List<String> externalProductIds = new ArrayList<>();
            for (CheckoutService.ItemCheckout item : plano.itens()) {
                String extId = item.externalProductId();
                if (extId == null || extId.isBlank()) {
                    var pr = gateway.createProduct(new PaymentGateway.ProductRequest(
                            item.productId().toString(), item.productName(), item.precoCentavos(), "BRL"));
                    checkoutService.registrarProdutoExterno(item.servicoId(), pr.id());
                    extId = pr.id();
                }
                externalProductIds.add(extId);
            }

            String checkoutId = plano.checkoutId();
            String checkoutUrl = plano.checkoutUrl();
            if (checkoutId == null || checkoutId.isBlank()) {
                var cr = gateway.createCheckout(new PaymentGateway.CheckoutRequest(
                        externalProductIds, metodos(), plano.customerId(), solicitacaoId.toString(),
                        rota("/pagamento/retorno", solicitacaoId), rota("/pagamento/concluido", solicitacaoId)));
                checkoutId = cr.id();
                checkoutUrl = cr.url();
            }

            checkoutService.registrarCheckoutPronto(solicitacaoId, checkoutId, checkoutUrl);
            // Pagamento.externalId = checkoutId -> o webhook (data.id = checkout id) casa em aplicarEvento.
            pagamentoService.aplicarCharge(pagamentoId, gateway.nome(), checkoutId, checkoutUrl);

            String destinatario = pagamentoService.emailDoDestinatario(solicitacaoId);
            if (destinatario != null) {
                long totalCentavos = plano.itens().stream().mapToLong(CheckoutService.ItemCheckout::precoCentavos).sum();
                String valor = java.math.BigDecimal.valueOf(totalCentavos).movePointLeft(2).toPlainString();
                outbox.publish("notify.requested.pagamento-link", Map.of(
                        "eventId", "pay-link-" + pagamentoId,
                        "canal", "email",
                        "tipoComunicacao", "financeiro",
                        "templateId", NotifyTemplates.PAGAMENTO_LINK,
                        "destinatario", destinatario,
                        "variaveis", Map.of("valor", valor, "checkoutUrl", checkoutUrl)));
            } else {
                log.warn("[payment] sem e-mail de destinatário para solicitacao={}, link não enviado", solicitacaoId);
            }
            log.info("[payment] checkout pronto solicitacao={} provider={} checkoutId={}",
                    solicitacaoId, gateway.nome(), checkoutId);
        } catch (RuntimeException ex) {
            // Provider fora do ar / payload inesperado: não re-lança (evita retry-storm no q.payment).
            // payment_status='erro' + o retrigger manual continua disponível.
            log.error("[payment] falha ao preparar checkout solicitacao={}", solicitacaoId, ex);
            checkoutService.marcarErro(solicitacaoId);
        }
    }

    private void processarWebhook(JsonNode e) {
        String provider = e.path("provider").asText();
        String payload = e.path("payload").asText();
        PaymentGateway gateway = resolver(provider);
        var evt = gateway.parseWebhook(payload, extrairHeaders(e.path("headers")));
        pagamentoService.aplicarEvento(evt.eventId(), evt.externalId(), evt.status());
        log.info("[payment] webhook aplicado externalId={} status={}", evt.externalId(), evt.status());
    }

    private void criarCustomer(JsonNode e) {
        UUID imobiliariaId = UUID.fromString(e.path("imobiliariaId").asText());
        PaymentGateway gateway = resolver(providerAtivo);
        try {
            String email = e.path("email").asText("");
            if (email.isBlank()) {
                log.warn("[payment] imobiliaria {} sem e-mail — customer não criado", imobiliariaId);
                return;
            }
            var cr = gateway.createCustomer(new PaymentGateway.CustomerRequest(
                    email, texto(e, "name"), texto(e, "taxId"), texto(e, "cellphone"), texto(e, "zipCode")));
            imobiliariaService.registrarClientePagamento(imobiliariaId, cr.id());
            log.info("[payment] customer criado imobiliaria={} customerId={}", imobiliariaId, cr.id());
        } catch (RuntimeException ex) {
            // Não bloqueia: a imobiliária já foi criada; fica sem customer até novo disparo.
            log.error("[payment] falha ao criar customer imobiliaria={}", imobiliariaId, ex);
        }
    }

    private void removerCustomer(JsonNode e) {
        String externalPaymentId = e.path("externalPaymentId").asText("");
        if (externalPaymentId.isBlank()) {
            log.warn("[payment] customer-delete sem externalPaymentId — ignorado");
            return;
        }
        resolver(providerAtivo).deleteCustomer(externalPaymentId);
        log.info("[payment] customer removido customerId={}", externalPaymentId);
    }

    private List<String> metodos() {
        return Arrays.stream(methods.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private String rota(String path, UUID solicitacaoId) {
        String base = appUrl.endsWith("/") ? appUrl.substring(0, appUrl.length() - 1) : appUrl;
        return base + path + "?solicitacao=" + solicitacaoId;
    }

    private static String texto(JsonNode e, String campo) {
        String v = e.path(campo).asText("");
        return v.isBlank() ? null : v;
    }

    private Map<String, String> extrairHeaders(JsonNode node) {
        Map<String, String> headers = new java.util.HashMap<>();
        node.properties().forEach(entry -> headers.put(entry.getKey(), entry.getValue().asText()));
        return headers;
    }

    private PaymentGateway resolver(String provider) {
        PaymentGateway g = gateways.get(provider);
        if (g == null) throw new IllegalArgumentException("Provider de pagamento desconhecido: " + provider);
        return g;
    }
}
