package br.com.vistoria.worker.payment;

import br.com.vistoria.core.events.EventTypes;
import br.com.vistoria.core.modules.financeiro.CheckoutService;
import br.com.vistoria.core.modules.financeiro.PagamentoService;
import br.com.vistoria.core.modules.imobiliaria.ImobiliariaService;
import br.com.vistoria.core.shared.outbox.OutboxService;
import br.com.vistoria.worker.payment.gateway.PaymentGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentConsumer")
class PaymentConsumerTest {

    @Mock private PagamentoService pagamentoService;
    @Mock private CheckoutService checkoutService;
    @Mock private ImobiliariaService imobiliariaService;
    @Mock private OutboxService outbox;
    @Mock private PaymentGateway mockGateway;
    @Mock private PaymentGateway abacatepayGateway;

    private PaymentConsumer consumer;

    @BeforeEach
    void setUp() {
        Map<String, PaymentGateway> gateways = Map.of("mock", mockGateway, "abacatepay", abacatepayGateway);
        consumer = new PaymentConsumer(gateways, pagamentoService, checkoutService, imobiliariaService, outbox);
        ReflectionTestUtils.setField(consumer, "providerAtivo", "mock");
        ReflectionTestUtils.setField(consumer, "methods", "PIX,CARD");
        ReflectionTestUtils.setField(consumer, "appUrl", "https://app.example.com");
    }

    private Message msg(String payload, String routingKey) {
        MessageProperties props = new MessageProperties();
        props.setReceivedRoutingKey(routingKey);
        return new Message(payload.getBytes(), props);
    }


    @Test
    @DisplayName("charge-requested: cria produto por serviço, cria checkout, grava e notifica")
    void chargeRequested_happyPath() {
        UUID pagamentoId = UUID.randomUUID();
        UUID solicitacaoId = UUID.randomUUID();
        UUID servicoId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        var plano = new CheckoutService.PlanoCheckout(solicitacaoId,
                List.of(new CheckoutService.ItemCheckout(servicoId, productId, null, "Vistoria de entrada - Elegance", 65000L)),
                "cust_9", null, null);
        when(checkoutService.planejar(solicitacaoId)).thenReturn(plano);
        when(mockGateway.nome()).thenReturn("mock");
        when(mockGateway.createProduct(any())).thenReturn(new PaymentGateway.ProductResult("prod_1", productId.toString()));
        when(mockGateway.createCheckout(any())).thenReturn(new PaymentGateway.CheckoutResult("chk_1", "https://pay/chk_1"));
        when(pagamentoService.emailDoDestinatario(solicitacaoId)).thenReturn("fin@imob.com");

        consumer.on(msg("""
            {"eventId":"e1","pagamentoId":"%s","solicitacaoId":"%s","valor":"650.00"}
            """.formatted(pagamentoId, solicitacaoId), EventTypes.PAYMENT_CHARGE_REQUESTED));

        verify(checkoutService).registrarProdutoExterno(servicoId, "prod_1");

        ArgumentCaptor<PaymentGateway.CheckoutRequest> req = ArgumentCaptor.forClass(PaymentGateway.CheckoutRequest.class);
        verify(mockGateway).createCheckout(req.capture());
        org.assertj.core.api.Assertions.assertThat(req.getValue().productIds()).containsExactly("prod_1");
        org.assertj.core.api.Assertions.assertThat(req.getValue().customerId()).isEqualTo("cust_9");
        org.assertj.core.api.Assertions.assertThat(req.getValue().returnUrl()).isEqualTo("https://app.example.com/pagamento/retorno?solicitacao=" + solicitacaoId);

        verify(checkoutService).registrarCheckoutPronto(solicitacaoId, "chk_1", "https://pay/chk_1");
        verify(pagamentoService).aplicarCharge(pagamentoId, "mock", "chk_1", "https://pay/chk_1");
        verify(outbox).publish(eq("notify.requested.pagamento-link"), any());
    }

    @Test
    @DisplayName("pix-requested: gera PIX no provider com dados do pagador e grava no pagamento")
    void pixRequested_gravaPix() {
        UUID pagamentoId = UUID.randomUUID();
        UUID solicitacaoId = UUID.randomUUID();
        ReflectionTestUtils.setField(consumer, "pixExpiresSeconds", 3600);
        when(mockGateway.nome()).thenReturn("mock");
        when(pagamentoService.pagadorDe(solicitacaoId))
                .thenReturn(new PagamentoService.Pagador("Ana Souza", "a@b.com", "52998224725", "11999990000"));
        var expira = java.time.Instant.parse("2026-10-02T12:00:00Z");
        when(mockGateway.createPix(any())).thenReturn(new PaymentGateway.PixResult("pix_1", "000201", "data:image/png;base64,AAA", expira));

        consumer.on(msg("""
            {"eventId":"e1","pagamentoId":"%s","solicitacaoId":"%s","valor":"270.00"}
            """.formatted(pagamentoId, solicitacaoId), EventTypes.PAYMENT_PIX_REQUESTED));

        org.mockito.ArgumentCaptor<PaymentGateway.PixRequest> cap = org.mockito.ArgumentCaptor.forClass(PaymentGateway.PixRequest.class);
        verify(mockGateway).createPix(cap.capture());
        assertThat(cap.getValue().amountCents()).isEqualTo(27000);
        assertThat(cap.getValue().customer().taxId()).isEqualTo("52998224725");
        verify(pagamentoService).registrarPix(pagamentoId, "mock", "pix_1", "000201", "data:image/png;base64,AAA", expira);
    }

    @Test
    @DisplayName("pix-requested: falha do provider não relança nem grava PIX")
    void pixRequested_falhaNaoRelanca() {
        UUID pagamentoId = UUID.randomUUID();
        UUID solicitacaoId = UUID.randomUUID();
        ReflectionTestUtils.setField(consumer, "pixExpiresSeconds", 3600);
        when(pagamentoService.pagadorDe(solicitacaoId)).thenReturn(null);
        when(mockGateway.createPix(any())).thenThrow(new IllegalStateException("provider fora"));

        consumer.on(msg("""
            {"eventId":"e1","pagamentoId":"%s","solicitacaoId":"%s","valor":"10.00"}
            """.formatted(pagamentoId, solicitacaoId), EventTypes.PAYMENT_PIX_REQUESTED));

        verify(pagamentoService, never()).registrarPix(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("charge-requested idempotente: serviço já tem external_product_id e checkout já existe")
    void chargeRequested_idempotent() {
        UUID pagamentoId = UUID.randomUUID();
        UUID solicitacaoId = UUID.randomUUID();
        UUID servicoId = UUID.randomUUID();

        var plano = new CheckoutService.PlanoCheckout(solicitacaoId,
                List.of(new CheckoutService.ItemCheckout(servicoId, UUID.randomUUID(), "prod_existing", "X", 1000L)),
                null, "chk_existing", "https://pay/chk_existing");
        when(checkoutService.planejar(solicitacaoId)).thenReturn(plano);
        when(mockGateway.nome()).thenReturn("mock");
        when(pagamentoService.emailDoDestinatario(solicitacaoId)).thenReturn(null);

        consumer.on(msg("""
            {"eventId":"e1","pagamentoId":"%s","solicitacaoId":"%s","valor":"10.00"}
            """.formatted(pagamentoId, solicitacaoId), EventTypes.PAYMENT_CHARGE_REQUESTED));

        verify(mockGateway, never()).createProduct(any());
        verify(mockGateway, never()).createCheckout(any());
        verify(checkoutService).registrarCheckoutPronto(solicitacaoId, "chk_existing", "https://pay/chk_existing");
    }

    @Test
    @DisplayName("charge-requested: falha de provider -> marcarErro, sem re-lançar")
    void chargeRequested_providerFailure() {
        UUID pagamentoId = UUID.randomUUID();
        UUID solicitacaoId = UUID.randomUUID();
        var plano = new CheckoutService.PlanoCheckout(solicitacaoId,
                List.of(new CheckoutService.ItemCheckout(UUID.randomUUID(), UUID.randomUUID(), null, "X", 1000L)),
                null, null, null);
        when(checkoutService.planejar(solicitacaoId)).thenReturn(plano);
        when(mockGateway.createProduct(any())).thenThrow(new IllegalStateException("provider down"));

        consumer.on(msg("""
            {"eventId":"e1","pagamentoId":"%s","solicitacaoId":"%s","valor":"10.00"}
            """.formatted(pagamentoId, solicitacaoId), EventTypes.PAYMENT_CHARGE_REQUESTED));

        verify(checkoutService).marcarErro(solicitacaoId);
        verify(checkoutService, never()).registrarCheckoutPronto(any(), any(), any());
    }


    @Test
    @DisplayName("webhook: parse + aplicarEvento")
    void webhook_appliesEvent() {
        when(mockGateway.parseWebhook(eq("data"), any()))
                .thenReturn(new PaymentGateway.PaymentEvent("evt-1", "chk_1", "pago"));

        consumer.on(msg("""
            {"provider":"mock","payload":"data","headers":{}}
            """, EventTypes.PAYMENT_WEBHOOK));

        verify(pagamentoService).aplicarEvento("evt-1", "chk_1", "pago");
    }


    @Test
    @DisplayName("customer-create: cria customer e grava external_payment_id")
    void customerCreate() {
        UUID imobiliariaId = UUID.randomUUID();
        when(mockGateway.createCustomer(any())).thenReturn(new PaymentGateway.CustomerResult("cust_42"));

        consumer.on(msg("""
            {"imobiliariaId":"%s","email":"fin@x.com","name":"X LTDA","taxId":"12345678000199","cellphone":"11999"}
            """.formatted(imobiliariaId), EventTypes.PAYMENT_CUSTOMER_CREATE));

        verify(imobiliariaService).registrarClientePagamento(imobiliariaId, "cust_42");
    }

    @Test
    @DisplayName("customer-create: falha de provider não propaga")
    void customerCreate_failureSwallowed() {
        UUID imobiliariaId = UUID.randomUUID();
        when(mockGateway.createCustomer(any())).thenThrow(new IllegalStateException("boom"));

        consumer.on(msg("""
            {"imobiliariaId":"%s","email":"fin@x.com"}
            """.formatted(imobiliariaId), EventTypes.PAYMENT_CUSTOMER_CREATE));

        verify(imobiliariaService, never()).registrarClientePagamento(any(), anyString());
    }

    @Test
    @DisplayName("customer-delete: chama gateway.deleteCustomer")
    void customerDelete() {
        consumer.on(msg("""
            {"externalPaymentId":"cust_42"}
            """, EventTypes.PAYMENT_CUSTOMER_DELETE));

        verify(mockGateway).deleteCustomer("cust_42");
    }

    @Test
    @DisplayName("checkout-ready: no-op (evento de domínio informativo)")
    void checkoutReady_noop() {
        consumer.on(msg("""
            {"solicitacaoId":"%s"}
            """.formatted(UUID.randomUUID()), EventTypes.PAYMENT_CHECKOUT_READY));

        verify(pagamentoService, never()).aplicarEvento(any(), any(), any());
    }

    @Test
    @DisplayName("webhook: provider desconhecido lança")
    void webhook_unknownProvider() {
        assertThatThrownBy(() -> consumer.on(msg("""
            {"provider":"nope","payload":"x","headers":{}}
            """, EventTypes.PAYMENT_WEBHOOK)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Provider de pagamento desconhecido");
    }
}
