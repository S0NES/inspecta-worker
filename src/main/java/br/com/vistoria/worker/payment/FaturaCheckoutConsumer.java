package br.com.vistoria.worker.payment;

import br.com.vistoria.core.events.EventTypes;
import br.com.vistoria.core.modules.financeiro.ExtratoService;
import br.com.vistoria.core.modules.financeiro.FaturaConsolidada;
import br.com.vistoria.core.modules.imobiliaria.Imobiliaria;
import br.com.vistoria.core.modules.imobiliaria.ImobiliariaRepository;
import br.com.vistoria.worker.payment.gateway.PaymentGateway;
import br.com.vistoria.worker.shared.Eventos;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Checkout da <b>fatura consolidada B2B</b> ({@code plano-b2b-billing.md}) — o I/O com o
 * provider de pagamento fora da thread de request do inspecta-api, igual ao {@code PaymentConsumer}.
 *
 * <ul>
 *   <li>{@code jobs.payment.fatura-charge-requested} -> <b>1 produto</b> AbacatePay com o
 *       {@code valorTotal} da fatura inteira (NAO 1 por item) + 1 checkout;
 *       {@link ExtratoService#registrarCheckoutFatura}.</li>
 *   <li>{@code jobs.payment.webhook-received} -> so quando {@code externalId} tem prefixo
 *       {@code "fatura-"}: {@link ExtratoService#marcarFaturaPaga}. <b>Nunca</b>
 *       {@code aplicarPagamentoConfirmado} (isso e do fluxo de {@code Solicitacao}).</li>
 * </ul>
 *
 * <p>O I/O com o provider roda <b>fora</b> de transação (o listener não é
 * {@code @Transactional}); só {@link ExtratoService#registrarCheckoutFatura} abre tx.
 * {@code registrarCheckoutFatura} é no-op se a fatura já está {@code checkout_ready}/{@code pago}.</p>
 *
 * <p>{@code externalId = "fatura-{uuid}"} e o que o webhook usa para distinguir de uma cobranca
 * de {@code Solicitacao}. Ver {@link PaymentFaturaRabbitConfig} para a topologia de fila.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FaturaCheckoutConsumer {

    /** Prefixo do {@code externalId} de produto/checkout de fatura — chave de roteamento do webhook. */
    public static final String FATURA_PREFIX = "fatura-";

    private final Map<String, PaymentGateway> gateways;
    private final ExtratoService extratoService;
    private final ImobiliariaRepository imobiliarias;

    @Value("${inspecta.payment.provider:mock}") private String providerAtivo;
    @Value("${inspecta.payment.methods:PIX,CARD}") private String methods;
    @Value("${inspecta.app-url:http://localhost:5173}") private String appUrl;

    @RabbitListener(queues = PaymentFaturaRabbitConfig.QUEUE, containerFactory = "paymentListenerFactory")
    public void on(Message message) {
        JsonNode e = Eventos.parse(message.getBody());
        String type = message.getMessageProperties().getReceivedRoutingKey();
        switch (type) {
            case EventTypes.PAYMENT_FATURA_CHARGE_REQUESTED -> prepararCheckoutFatura(e);
            case EventTypes.PAYMENT_WEBHOOK -> processarWebhook(e);
            default -> log.warn("[fatura] routing key inesperada em {}: {}", PaymentFaturaRabbitConfig.QUEUE, type);
        }
    }

    private void prepararCheckoutFatura(JsonNode e) {
        UUID faturaId = UUID.fromString(e.path("faturaId").asText());
        UUID imobiliariaId = UUID.fromString(e.path("imobiliariaId").asText());

        FaturaConsolidada fatura = extratoService.listarFaturas(imobiliariaId).stream()
                .filter(f -> faturaId.equals(f.getId()))
                .findFirst()
                .orElse(null);
        if (fatura == null) {
            log.warn("[fatura] fatura {} nao encontrada para imobiliaria {} — checkout nao criado",
                    faturaId, imobiliariaId);
            return;
        }
        if (!"pendente".equals(fatura.getStatus()) || fatura.getCheckoutId() != null) {
            log.info("[fatura] {} ja em status '{}' checkoutId={} — checkout nao refeito (idempotente)",
                    faturaId, fatura.getStatus(), fatura.getCheckoutId());
            return;
        }

        BigDecimal valorTotal = fatura.getValorTotal() != null
                ? fatura.getValorTotal()
                : new BigDecimal(e.path("valorTotal").asText("0"));
        long centavos = valorTotal.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
        if (centavos <= 0) {
            log.warn("[fatura] {} com valor total {} — checkout nao criado", faturaId, valorTotal);
            return;
        }

        String externalId = FATURA_PREFIX + faturaId;
        PaymentGateway gateway = resolver(providerAtivo);
        try {
            var produto = gateway.createProduct(new PaymentGateway.ProductRequest(
                    externalId, "Fatura consolidada Inspecta " + faturaId, centavos, "BRL"));

            String customerId = imobiliarias.findById(imobiliariaId)
                    .map(Imobiliaria::getExternalPaymentId)
                    .filter(s -> s != null && !s.isBlank())
                    .orElse(null);

            var checkout = gateway.createCheckout(new PaymentGateway.CheckoutRequest(
                    List.of(produto.id()), metodos(), customerId, externalId,
                    rota(faturaId), rota(faturaId)));

            extratoService.registrarCheckoutFatura(faturaId, checkout.id(), checkout.url());
            log.info("[fatura] checkout pronto fatura={} provider={} checkoutId={} total={}",
                    faturaId, gateway.nome(), checkout.id(), valorTotal);
        } catch (RuntimeException ex) {
            // Igual ao PaymentConsumer: nao re-lanca (evita retry-storm). A fatura fica 'pendente'
            // e o disparo pode ser refeito.
            log.error("[fatura] ALERTA: falha ao preparar checkout fatura={}", faturaId, ex);
        }
    }

    private void processarWebhook(JsonNode e) {
        String provider = e.path("provider").asText();
        String payload = e.path("payload").asText();
        PaymentGateway gateway = resolver(provider);
        PaymentGateway.PaymentEvent evt = gateway.parseWebhook(payload, extrairHeaders(e.path("headers")));

        String externalId = evt.externalId();
        // Dois formatos: "fatura-<uuid>" (externalId nosso) ou o id do checkout do provedor
        // (AbacatePay manda data.id = id do checkout) — resolvido pelo checkout_id gravado na fatura.
        UUID faturaId = null;
        if (externalId != null && externalId.startsWith(FATURA_PREFIX)) {
            faturaId = UUID.fromString(externalId.substring(FATURA_PREFIX.length()));
        } else if (externalId != null) {
            faturaId = extratoService.faturaIdPorCheckout(externalId).orElse(null);
        }
        if (faturaId == null) {
            return; // webhook de Solicitacao — quem trata e o PaymentConsumer em q.payment
        }
        if (!"pago".equals(evt.status())) {
            log.info("[fatura] webhook {} status='{}' — ignorado (so 'pago' fecha a fatura)", externalId, evt.status());
            return;
        }
        extratoService.marcarFaturaPaga(faturaId);
        log.info("[fatura] fatura {} marcada como paga (webhook evt={})", faturaId, evt.eventId());
    }

    private List<String> metodos() {
        return Arrays.stream(methods.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private String rota(UUID faturaId) {
        String base = appUrl.endsWith("/") ? appUrl.substring(0, appUrl.length() - 1) : appUrl;
        return base + "/extrato/faturas?fatura=" + faturaId;
    }

    private Map<String, String> extrairHeaders(JsonNode node) {
        Map<String, String> headers = new HashMap<>();
        node.properties().forEach(entry -> headers.put(entry.getKey(), entry.getValue().asText()));
        return headers;
    }

    private PaymentGateway resolver(String provider) {
        PaymentGateway g = gateways.get(provider);
        if (g == null) throw new IllegalArgumentException("Provider de pagamento desconhecido: " + provider);
        return g;
    }
}
