package br.com.vistoria.worker.payment;

import br.com.vistoria.core.events.EventTypes;
import br.com.vistoria.core.modules.financeiro.ExtratoService;
import br.com.vistoria.core.modules.financeiro.FaturaConsolidada;
import br.com.vistoria.core.modules.imobiliaria.Imobiliaria;
import br.com.vistoria.core.modules.imobiliaria.ImobiliariaRepository;
import br.com.vistoria.worker.payment.gateway.PaymentGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FaturaCheckoutConsumer — checkout + webhook da fatura consolidada B2B")
class FaturaCheckoutConsumerTest {

    @Mock private PaymentGateway mockGateway;
    @Mock private ExtratoService extratoService;
    @Mock private ImobiliariaRepository imobiliarias;

    private FaturaCheckoutConsumer consumer;

    private final UUID faturaId = UUID.randomUUID();
    private final UUID imobiliariaId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        consumer = new FaturaCheckoutConsumer(Map.of("mock", mockGateway), extratoService, imobiliarias);
        ReflectionTestUtils.setField(consumer, "providerAtivo", "mock");
        ReflectionTestUtils.setField(consumer, "methods", "PIX,CARD");
        ReflectionTestUtils.setField(consumer, "appUrl", "https://app.example.com");
        when(mockGateway.nome()).thenReturn("mock");
    }

    private Message msg(String payload, String routingKey) {
        MessageProperties props = new MessageProperties();
        props.setReceivedRoutingKey(routingKey);
        return new Message(payload.getBytes(), props);
    }

    private FaturaConsolidada fatura(String status, BigDecimal total) {
        FaturaConsolidada f = new FaturaConsolidada();
        f.setId(faturaId);
        f.setImobiliariaId(imobiliariaId);
        f.setStatus(status);
        f.setValorTotal(total);
        return f;
    }

    @Test
    @DisplayName("charge-requested: 1 produto com o valor total (NAO 1 por item) + 1 checkout + registrarCheckoutFatura")
    void chargeRequested_umProdutoUmCheckout() {
        when(extratoService.listarFaturas(imobiliariaId))
                .thenReturn(List.of(fatura("pendente", new BigDecimal("450.00"))));
        when(imobiliarias.findById(imobiliariaId)).thenReturn(Optional.empty());
        when(mockGateway.createProduct(any())).thenReturn(new PaymentGateway.ProductResult("prod_1", "ext"));
        when(mockGateway.createCheckout(any())).thenReturn(new PaymentGateway.CheckoutResult("chk_1", "https://pay/chk_1"));

        consumer.on(msg("""
            {"eventId":"e1","faturaId":"%s","imobiliariaId":"%s","valorTotal":450.00}
            """.formatted(faturaId, imobiliariaId), EventTypes.PAYMENT_FATURA_CHARGE_REQUESTED));

        ArgumentCaptor<PaymentGateway.ProductRequest> prod = ArgumentCaptor.forClass(PaymentGateway.ProductRequest.class);
        verify(mockGateway).createProduct(prod.capture());
        assertThat(prod.getValue().priceCents()).isEqualTo(45000L);
        assertThat(prod.getValue().externalId()).isEqualTo("fatura-" + faturaId);

        ArgumentCaptor<PaymentGateway.CheckoutRequest> chk = ArgumentCaptor.forClass(PaymentGateway.CheckoutRequest.class);
        verify(mockGateway).createCheckout(chk.capture());
        assertThat(chk.getValue().productIds()).containsExactly("prod_1");
        assertThat(chk.getValue().externalId()).isEqualTo("fatura-" + faturaId);

        verify(extratoService).registrarCheckoutFatura(faturaId, "chk_1", "https://pay/chk_1");
    }

    @Test
    @DisplayName("charge-requested idempotente: fatura ja checkout_ready -> nenhuma chamada ao provider")
    void chargeRequested_idempotente() {
        when(extratoService.listarFaturas(imobiliariaId))
                .thenReturn(List.of(fatura("checkout_ready", new BigDecimal("450.00"))));

        consumer.on(msg("""
            {"eventId":"e1","faturaId":"%s","imobiliariaId":"%s","valorTotal":450.00}
            """.formatted(faturaId, imobiliariaId), EventTypes.PAYMENT_FATURA_CHARGE_REQUESTED));

        verify(mockGateway, never()).createProduct(any());
        verify(mockGateway, never()).createCheckout(any());
        verify(extratoService, never()).registrarCheckoutFatura(any(), any(), any());
    }

    @Test
    @DisplayName("charge-requested idempotente: pendente mas ja tem checkoutId -> nao chama o provider")
    void chargeRequested_pendenteComCheckoutId_noop() {
        FaturaConsolidada f = fatura("pendente", new BigDecimal("450.00"));
        f.setCheckoutId("chk_ja_existe");
        when(extratoService.listarFaturas(imobiliariaId)).thenReturn(List.of(f));

        consumer.on(msg("""
            {"eventId":"e1","faturaId":"%s","imobiliariaId":"%s","valorTotal":450.00}
            """.formatted(faturaId, imobiliariaId), EventTypes.PAYMENT_FATURA_CHARGE_REQUESTED));

        verify(mockGateway, never()).createProduct(any());
        verify(mockGateway, never()).createCheckout(any());
        verify(extratoService, never()).registrarCheckoutFatura(any(), any(), any());
    }

    @Test
    @DisplayName("charge-requested: usa customerId da imobiliaria quando ha externalPaymentId")
    void chargeRequested_comCustomer() {
        Imobiliaria imob = new Imobiliaria();
        ReflectionTestUtils.setField(imob, "externalPaymentId", "cust_77");
        when(extratoService.listarFaturas(imobiliariaId)).thenReturn(List.of(fatura("pendente", new BigDecimal("10.00"))));
        when(imobiliarias.findById(imobiliariaId)).thenReturn(Optional.of(imob));
        when(mockGateway.createProduct(any())).thenReturn(new PaymentGateway.ProductResult("prod_1", "ext"));
        when(mockGateway.createCheckout(any())).thenReturn(new PaymentGateway.CheckoutResult("chk_1", "u"));

        consumer.on(msg("""
            {"eventId":"e1","faturaId":"%s","imobiliariaId":"%s","valorTotal":10.00}
            """.formatted(faturaId, imobiliariaId), EventTypes.PAYMENT_FATURA_CHARGE_REQUESTED));

        ArgumentCaptor<PaymentGateway.CheckoutRequest> chk = ArgumentCaptor.forClass(PaymentGateway.CheckoutRequest.class);
        verify(mockGateway).createCheckout(chk.capture());
        assertThat(chk.getValue().customerId()).isEqualTo("cust_77");
    }

    @Test
    @DisplayName("charge-requested: falha de provider nao re-lanca (sem retry-storm), fatura fica pendente")
    void chargeRequested_falhaProvider() {
        when(extratoService.listarFaturas(imobiliariaId)).thenReturn(List.of(fatura("pendente", new BigDecimal("10.00"))));
        when(mockGateway.createProduct(any())).thenThrow(new IllegalStateException("provider down"));

        consumer.on(msg("""
            {"eventId":"e1","faturaId":"%s","imobiliariaId":"%s","valorTotal":10.00}
            """.formatted(faturaId, imobiliariaId), EventTypes.PAYMENT_FATURA_CHARGE_REQUESTED));

        verify(extratoService, never()).registrarCheckoutFatura(any(), any(), any());
    }

    @Test
    @DisplayName("webhook checkout.completed de fatura (externalId 'fatura-...' + pago) -> marcarFaturaPaga, NUNCA aplicarPagamentoConfirmado")
    void webhook_faturaPaga() {
        when(mockGateway.parseWebhook(anyString(), any()))
                .thenReturn(new PaymentGateway.PaymentEvent("evt-1", "fatura-" + faturaId, "pago"));

        consumer.on(msg("""
            {"provider":"mock","payload":"x","headers":{}}
            """, EventTypes.PAYMENT_WEBHOOK));

        verify(extratoService).marcarFaturaPaga(faturaId);
    }

    @Test
    @DisplayName("webhook com id do checkout do provedor (sem prefixo) resolve a fatura por checkout_id -> marcarFaturaPaga")
    void webhook_faturaPagaPorCheckoutId() {
        when(mockGateway.parseWebhook(anyString(), any()))
                .thenReturn(new PaymentGateway.PaymentEvent("evt-9", "bill_chk_123", "pago"));
        when(extratoService.faturaIdPorCheckout("bill_chk_123")).thenReturn(java.util.Optional.of(faturaId));

        consumer.on(msg("""
            {"provider":"mock","payload":"x","headers":{}}
            """, EventTypes.PAYMENT_WEBHOOK));

        verify(extratoService).marcarFaturaPaga(faturaId);
    }

    @Test
    @DisplayName("webhook de Solicitacao (externalId sem prefixo 'fatura-'): ignorado por este consumer")
    void webhook_solicitacaoIgnorado() {
        when(mockGateway.parseWebhook(anyString(), any()))
                .thenReturn(new PaymentGateway.PaymentEvent("evt-2", "chk_solicitacao_1", "pago"));

        consumer.on(msg("""
            {"provider":"mock","payload":"x","headers":{}}
            """, EventTypes.PAYMENT_WEBHOOK));

        verify(extratoService, never()).marcarFaturaPaga(any());
    }

    @Test
    @DisplayName("webhook de fatura com status != pago: ignorado")
    void webhook_faturaStatusNaoPago() {
        when(mockGateway.parseWebhook(anyString(), any()))
                .thenReturn(new PaymentGateway.PaymentEvent("evt-3", "fatura-" + faturaId, "estornado"));

        consumer.on(msg("""
            {"provider":"mock","payload":"x","headers":{}}
            """, EventTypes.PAYMENT_WEBHOOK));

        verify(extratoService, never()).marcarFaturaPaga(any());
    }

    @Test
    @DisplayName("routing key inesperada: no-op")
    void routingKeyInesperada() {
        consumer.on(msg("{}", "jobs.payment.charge-requested"));
        verify(mockGateway, never()).createProduct(any());
        verify(extratoService, never()).marcarFaturaPaga(any());
    }

    @Test
    @DisplayName("prefixo FATURA_PREFIX estavel = 'fatura-'")
    void prefixoEstavel() {
        assertThat(FaturaCheckoutConsumer.FATURA_PREFIX).isEqualTo("fatura-");
    }
}
