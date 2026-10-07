package br.com.vistoria.worker.payment.gateway;

import br.com.vistoria.core.gateway.HttpGateway;
import br.com.vistoria.core.gateway.HttpGatewayRequest;
import br.com.vistoria.core.gateway.HttpGatewayResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbacatePayGateway")
class AbacatePayGatewayTest {

    @Mock private HttpGateway httpGateway;

    private AbacatePayGateway gateway;
    private ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        gateway = new AbacatePayGateway(httpGateway);
        ReflectionTestUtils.setField(gateway, "apiKey", "sk-test-12345");
        ReflectionTestUtils.setField(gateway, "paymentMethods", List.of("PIX", "CARD"));
    }

    @Test
    @DisplayName("nome returns 'abacatepay'")
    void nome_returnsCorrectName() {
        assertThat(gateway.nome()).isEqualTo("abacatepay");
    }

    @Test
    @DisplayName("configurado returns true when apiKey is set")
    void configurado_withValidKeys_returnsTrue() {
        assertThat(gateway.configurado()).isTrue();
    }

    @Test
    @DisplayName("configurado returns false when apiKey is blank")
    void configurado_withBlankApiKey_returnsFalse() {
        ReflectionTestUtils.setField(gateway, "apiKey", "");
        assertThat(gateway.configurado()).isFalse();
    }

    @Test
    @DisplayName("validarMetodosPagamento passes with valid methods")
    void validarMetodosPagamento_validMethods_doesNotThrow() {
        ReflectionTestUtils.setField(gateway, "paymentMethods", List.of("PIX", "CARD", "BOLETO"));
        gateway.validarMetodosPagamento();
    }

    @Test
    @DisplayName("validarMetodosPagamento throws when a method is not accepted by AbacatePay")
    void validarMetodosPagamento_invalidMethod_throws() {
        ReflectionTestUtils.setField(gateway, "paymentMethods", List.of("PIX", "BITCOIN"));

        assertThatThrownBy(() -> gateway.validarMetodosPagamento())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("BITCOIN");
    }

    @Test
    @DisplayName("validarMetodosPagamento throws when methods list is empty")
    void validarMetodosPagamento_emptyList_throws() {
        ReflectionTestUtils.setField(gateway, "paymentMethods", List.of());

        assertThatThrownBy(() -> gateway.validarMetodosPagamento())
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("createCharge creates product and payment link with correct values, forwarding methods as-is")
    void createCharge_validInput_returnsChargeResult() throws Exception {
        BigDecimal valor = new BigDecimal("299.90");
        Map<String, Object> metadata = Map.of("solicitacaoId", 50L);

        String produtoResponse = "{\"success\": true, \"data\": {\"id\": \"prod-123\"}}";
        String paymentLinkResponse = "{\"success\": true, \"data\": {\"id\": \"bill_456\", \"url\": \"https://pay.example.com/bill_456\"}}";

        when(httpGateway.execute(any())).thenReturn(
            new HttpGatewayResponse(200, Map.of(), produtoResponse),
            new HttpGatewayResponse(200, Map.of(), paymentLinkResponse)
        );

        PaymentGateway.ChargeResult result = gateway.createCharge(valor, metadata);

        assertThat(result.externalId()).isEqualTo("bill_456");
        assertThat(result.checkoutUrl()).isEqualTo("https://pay.example.com/bill_456");

        var captor = org.mockito.ArgumentCaptor.forClass(HttpGatewayRequest.class);
        verify(httpGateway, org.mockito.Mockito.times(2)).execute(captor.capture());
        HttpGatewayRequest productRequest = captor.getAllValues().get(0);
        HttpGatewayRequest paymentLinkRequest = captor.getAllValues().get(1);

        assertThat(productRequest.uri().toString()).endsWith("/products/create");
        assertThat(paymentLinkRequest.uri().toString()).endsWith("/payment-links/create");

        var paymentLinkBody = mapper.readTree(paymentLinkRequest.body());
        assertThat(paymentLinkBody.path("frequency").asText()).isEqualTo("MULTIPLE_PAYMENTS");
        assertThat(paymentLinkBody.path("methods")).extracting(com.fasterxml.jackson.databind.JsonNode::asText)
            .containsExactly("PIX", "CARD");
        assertThat(paymentLinkBody.path("items").get(0).path("id").asText()).isEqualTo("prod-123");
        assertThat(paymentLinkBody.path("items").get(0).path("quantity").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("createCharge throws when product creation fails")
    void createCharge_productCreationFails_throwsException() throws Exception {
        BigDecimal valor = new BigDecimal("100.00");
        Map<String, Object> metadata = Map.of("solicitacaoId", 50L);

        String errorResponse = "{\"success\": false, \"error\": \"Invalid product\"}";
        when(httpGateway.execute(any())).thenReturn(
            new HttpGatewayResponse(400, Map.of(), errorResponse)
        );

        assertThatThrownBy(() -> gateway.createCharge(valor, metadata))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("abacatepay createCharge falhou");
    }

    @Test
    @DisplayName("getStatus returns status from API via payment-links/get")
    void getStatus_validId_returnsStatus() throws Exception {
        String externalId = "bill_789";
        String statusResponse = "{\"success\": true, \"data\": {\"status\": \"PAID\"}}";

        var captor = org.mockito.ArgumentCaptor.forClass(HttpGatewayRequest.class);
        when(httpGateway.execute(any())).thenReturn(
            new HttpGatewayResponse(200, Map.of(), statusResponse)
        );

        String status = gateway.getStatus(externalId);

        assertThat(status).isEqualTo("PAID");
        verify(httpGateway).execute(captor.capture());
        assertThat(captor.getValue().uri().toString()).contains("/payment-links/get?id=" + externalId);
    }

    @Test
    @DisplayName("getStatus throws when API returns error")
    void getStatus_apiError_throwsException() throws Exception {
        when(httpGateway.execute(any())).thenReturn(
            new HttpGatewayResponse(500, Map.of(), "Internal Server Error")
        );

        assertThatThrownBy(() -> gateway.getStatus("bill-error"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("abacatepay getStatus falhou");
    }

    @Test
    @DisplayName("refund calls payment-links/refund endpoint with correct ID")
    void refund_validId_callsRefundEndpoint() throws Exception {
        String externalId = "bill_refund";
        String refundResponse = "{\"success\": true, \"data\": {\"refundPublicId\": \"tran_refund789\"}}";

        var captor = org.mockito.ArgumentCaptor.forClass(HttpGatewayRequest.class);
        when(httpGateway.execute(any())).thenReturn(
            new HttpGatewayResponse(200, Map.of(), refundResponse)
        );

        gateway.refund(externalId);

        verify(httpGateway).execute(captor.capture());
        assertThat(captor.getValue().uri().toString()).endsWith("/payment-links/refund");
    }

    @Test
    @DisplayName("refund throws when the API reports failure")
    void refund_apiError_throwsException() throws Exception {
        String errorResponse = "{\"success\": false, \"error\": \"TRANSACTION_NOT_FOUND\"}";

        when(httpGateway.execute(any())).thenReturn(
            new HttpGatewayResponse(404, Map.of(), errorResponse)
        );

        assertThatThrownBy(() -> gateway.refund("bill-missing"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("abacatepay refund falhou");
    }

    @Test
    @DisplayName("parseWebhook parses payload without checking any signature")
    void parseWebhook_noSignatureRequired_parsesEvent() {
        String webhookPayload = "{\"id\": \"evt-123\", \"event\": \"checkout.completed\", \"apiVersion\": 2, \"data\": {\"id\": \"bill_456\"}}";

        // No headers at all — the gateway must not require or check any signature header,
        // since authenticity is enforced once, at the inspecta-messenger edge (?webhookSecret=).
        PaymentGateway.PaymentEvent event = gateway.parseWebhook(webhookPayload, Map.of());

        assertThat(event.eventId()).isEqualTo("evt-123");
        assertThat(event.externalId()).isEqualTo("bill_456");
        assertThat(event.status()).isEqualTo("pago");
    }

    @Test
    @DisplayName("parseWebhook transparent.completed (PIX): usa data.transparent.id quando existe, senão data.id")
    void parseWebhook_transparentCompleted() {
        var aninhado = gateway.parseWebhook(
                "{\"id\":\"log_1\",\"event\":\"transparent.completed\",\"data\":{\"id\":\"outro\",\"transparent\":{\"id\":\"pix_char_1\"}}}", Map.of());
        assertThat(aninhado.externalId()).isEqualTo("pix_char_1");
        assertThat(aninhado.status()).isEqualTo("pago");

        var plano = gateway.parseWebhook(
                "{\"id\":\"log_2\",\"event\":\"transparent.completed\",\"data\":{\"id\":\"pix_char_2\"}}", Map.of());
        assertThat(plano.externalId()).isEqualTo("pix_char_2");
    }

    @Test
    @DisplayName("parseWebhook transparent.refunded -> 'estornado'")
    void parseWebhook_transparentRefunded() {
        var ev = gateway.parseWebhook(
                "{\"id\":\"log_3\",\"event\":\"transparent.refunded\",\"data\":{\"id\":\"pix_char_3\"}}", Map.of());
        assertThat(ev.status()).isEqualTo("estornado");
    }

    @Test
    @DisplayName("createPix: POST /transparents/create com method PIX + customer completo; devolve brCode/QR/expiração")
    void createPix_ok() throws Exception {
        String resp = "{\"success\":true,\"data\":{\"id\":\"pix_char_9\",\"brCode\":\"000201BR\","
                + "\"brCodeBase64\":\"data:image/png;base64,AAA\",\"expiresAt\":\"2026-10-02T12:00:00.000Z\"}}";
        org.mockito.ArgumentCaptor<br.com.vistoria.core.gateway.HttpGatewayRequest> cap =
                org.mockito.ArgumentCaptor.forClass(br.com.vistoria.core.gateway.HttpGatewayRequest.class);
        when(httpGateway.execute(cap.capture())).thenReturn(new HttpGatewayResponse(200, Map.of(), resp));

        var r = gateway.createPix(new PaymentGateway.PixRequest(27000, 3600, "Vistoria", Map.of("solicitacaoId", "s1"),
                new PaymentGateway.CustomerRequest("a@b.com", "Ana Souza", "52998224725", "11999990000", null)));

        assertThat(r.id()).isEqualTo("pix_char_9");
        assertThat(r.brCode()).isEqualTo("000201BR");
        assertThat(r.qrBase64()).startsWith("data:image/png;base64,");
        assertThat(r.expiresAt()).isEqualTo(java.time.Instant.parse("2026-10-02T12:00:00Z"));
        var req = cap.getValue();
        assertThat(req.uri().toString()).endsWith("/transparents/create");
        var body = mapper.readTree(req.body());
        assertThat(body.path("method").asText()).isEqualTo("PIX");
        assertThat(body.path("data").path("amount").asLong()).isEqualTo(27000);
        assertThat(body.path("data").path("customer").path("taxId").asText()).isEqualTo("52998224725");
    }

    @Test
    @DisplayName("pixStatus normaliza PAID/EXPIRED/PENDING")
    void pixStatus_normaliza() throws Exception {
        when(httpGateway.execute(any())).thenReturn(
                new HttpGatewayResponse(200, Map.of(), "{\"success\":true,\"data\":{\"status\":\"PAID\"}}"),
                new HttpGatewayResponse(200, Map.of(), "{\"success\":true,\"data\":{\"status\":\"EXPIRED\"}}"),
                new HttpGatewayResponse(200, Map.of(), "{\"success\":true,\"data\":{\"status\":\"PENDING\"}}"));

        assertThat(gateway.pixStatus("p")).isEqualTo("pago");
        assertThat(gateway.pixStatus("p")).isEqualTo("expirado");
        assertThat(gateway.pixStatus("p")).isEqualTo("pendente");
    }

    @Test
    @DisplayName("parseWebhook throws on unsupported webhook event")
    void parseWebhook_unsupportedEvent_throwsException() {
        String webhookPayload = "{\"id\": \"evt-123\", \"event\": \"unknown.event\", \"data\": {\"id\": \"bill_456\"}}";

        assertThatThrownBy(() -> gateway.parseWebhook(webhookPayload, Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nao suportado");
    }

    @Test
    @DisplayName("parseWebhook maps checkout.refunded event to 'estornado'")
    void parseWebhook_refundedEvent_mapsToEstornado() {
        String webhookPayload = "{\"id\": \"evt-refund\", \"event\": \"checkout.refunded\", \"data\": {\"id\": \"bill_789\"}}";

        PaymentGateway.PaymentEvent event = gateway.parseWebhook(webhookPayload, Map.of());

        assertThat(event.status()).isEqualTo("estornado");
    }

    @Test
    @DisplayName("parseWebhook throws (without leaking a parser exception) when the payload isn't valid JSON")
    void parseWebhook_malformedJsonPayload_throwsException() {
        String malformedPayload = "{not valid json";

        assertThatThrownBy(() -> gateway.parseWebhook(malformedPayload, Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("payload de webhook abacatepay invalido");
    }


    @Test
    @DisplayName("createProduct POSTs /products/create com price em centavos e devolve data.id")
    void createProduct_ok() throws Exception {
        when(httpGateway.execute(any())).thenReturn(
            new HttpGatewayResponse(200, Map.of(), "{\"success\":true,\"data\":{\"id\":\"prod_x\"}}"));

        var captor = org.mockito.ArgumentCaptor.forClass(HttpGatewayRequest.class);
        var res = gateway.createProduct(new PaymentGateway.ProductRequest("sku-1", "Vistoria - Elegance", 65000L, "BRL"));

        assertThat(res.id()).isEqualTo("prod_x");
        verify(httpGateway).execute(captor.capture());
        assertThat(captor.getValue().uri().toString()).endsWith("/products/create");
        var body = mapper.readTree(captor.getValue().body());
        assertThat(body.path("externalId").asText()).isEqualTo("sku-1");
        assertThat(body.path("price").asLong()).isEqualTo(65000L);
        assertThat(body.path("currency").asText()).isEqualTo("BRL");
    }

    @Test
    @DisplayName("createCheckout POSTs /checkouts/create com items, methods, returnUrl/completionUrl e omite customerId nulo")
    void createCheckout_ok() throws Exception {
        when(httpGateway.execute(any())).thenReturn(
            new HttpGatewayResponse(200, Map.of(), "{\"success\":true,\"data\":{\"id\":\"chk_1\",\"url\":\"https://pay/chk_1\"}}"));

        var captor = org.mockito.ArgumentCaptor.forClass(HttpGatewayRequest.class);
        var res = gateway.createCheckout(new PaymentGateway.CheckoutRequest(
            List.of("prod_a", "prod_b"), List.of("PIX"), null, "sol-1",
            "https://app/x/retorno", "https://app/x/concluido"));

        assertThat(res.id()).isEqualTo("chk_1");
        assertThat(res.url()).isEqualTo("https://pay/chk_1");
        verify(httpGateway).execute(captor.capture());
        assertThat(captor.getValue().uri().toString()).endsWith("/checkouts/create");
        var body = mapper.readTree(captor.getValue().body());
        assertThat(body.path("items").get(0).path("id").asText()).isEqualTo("prod_a");
        assertThat(body.path("items").get(1).path("quantity").asInt()).isEqualTo(1);
        assertThat(body.path("externalId").asText()).isEqualTo("sol-1");
        assertThat(body.path("returnUrl").asText()).isEqualTo("https://app/x/retorno");
        assertThat(body.has("customerId")).isFalse();
    }

    @Test
    @DisplayName("createCustomer POSTs /customers/create e devolve data.id")
    void createCustomer_ok() throws Exception {
        when(httpGateway.execute(any())).thenReturn(
            new HttpGatewayResponse(200, Map.of(), "{\"success\":true,\"data\":{\"id\":\"cust_1\"}}"));

        var captor = org.mockito.ArgumentCaptor.forClass(HttpGatewayRequest.class);
        var res = gateway.createCustomer(new PaymentGateway.CustomerRequest(
            "fin@x.com", "X LTDA", "12345678000199", "11999998888", null));

        assertThat(res.id()).isEqualTo("cust_1");
        verify(httpGateway).execute(captor.capture());
        assertThat(captor.getValue().uri().toString()).endsWith("/customers/create");
        var body = mapper.readTree(captor.getValue().body());
        assertThat(body.path("email").asText()).isEqualTo("fin@x.com");
        assertThat(body.path("taxId").asText()).isEqualTo("12345678000199");
        assertThat(body.has("zipCode")).isFalse();
    }

    @Test
    @DisplayName("deleteCustomer POSTs /customers/delete com o id")
    void deleteCustomer_ok() throws Exception {
        when(httpGateway.execute(any())).thenReturn(
            new HttpGatewayResponse(200, Map.of(), "{\"success\":true,\"data\":null}"));

        var captor = org.mockito.ArgumentCaptor.forClass(HttpGatewayRequest.class);
        gateway.deleteCustomer("cust_1");

        verify(httpGateway).execute(captor.capture());
        assertThat(captor.getValue().uri().toString()).endsWith("/customers/delete");
        assertThat(mapper.readTree(captor.getValue().body()).path("id").asText()).isEqualTo("cust_1");
    }

    @Test
    @DisplayName("createCheckout lança IllegalStateException quando o provider falha")
    void createCheckout_failure() throws Exception {
        when(httpGateway.execute(any())).thenReturn(
            new HttpGatewayResponse(400, Map.of(), "{\"success\":false,\"error\":\"bad\"}"));

        assertThatThrownBy(() -> gateway.createCheckout(new PaymentGateway.CheckoutRequest(
            List.of("prod_a"), List.of("PIX"), null, "sol-1", null, null)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("abacatepay createCheckout falhou");
    }
}
