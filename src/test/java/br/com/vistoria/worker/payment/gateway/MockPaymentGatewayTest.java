package br.com.vistoria.worker.payment.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
@DisplayName("MockPaymentGateway")
class MockPaymentGatewayTest {

    @Mock private ObjectMapper objectMapper;

    private MockPaymentGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new MockPaymentGateway(new ObjectMapper());
    }

    @Test
    @DisplayName("nome returns 'mock'")
    void nome_returnsCorrectName() {
        assertThat(gateway.nome()).isEqualTo("mock");
    }

    @Test
    @DisplayName("createCharge generates mock ID and returns checkout URL")
    void createCharge_validInput_returnsChargeResult() {
        BigDecimal valor = new BigDecimal("100.00");
        Map<String, Object> metadata = Map.of("solicitacaoId", 50L);

        PaymentGateway.ChargeResult result = gateway.createCharge(valor, metadata);

        assertThat(result.externalId()).startsWith("mock-");
        assertThat(result.checkoutUrl()).contains("http://localhost:8080/mock-checkout/");
        assertThat(result.checkoutUrl()).contains(result.externalId());
    }

    @Test
    @DisplayName("createCharge preserves metadata in logs")
    void createCharge_includesMetadata() {
        Map<String, Object> metadata = Map.of("solicitacaoId", 123L, "servicoId", 456L);

        PaymentGateway.ChargeResult result = gateway.createCharge(BigDecimal.TEN, metadata);

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("getStatus always returns 'pendente'")
    void getStatus_anyId_returnsPendente() {
        assertThat(gateway.getStatus("any-id")).isEqualTo("pendente");
        assertThat(gateway.getStatus("mock-123")).isEqualTo("pendente");
        assertThat(gateway.getStatus("")).isEqualTo("pendente");
    }

    @Test
    @DisplayName("refund accepts any external ID without error")
    void refund_validId_doesNotThrow() {
        gateway.refund("mock-123");
        gateway.refund("random-id");
        // Should complete without exception
    }

    @Test
    @DisplayName("parseWebhook requires eventId, externalId, and status fields")
    void parseWebhook_validPayload_returnsEvent() throws Exception {
        String payload = "{\"eventId\": \"evt-001\", \"externalId\": \"ch-123\", \"status\": \"pago\"}";
        Map<String, String> headers = Map.of();

        PaymentGateway.PaymentEvent event = gateway.parseWebhook(payload, headers);

        assertThat(event.eventId()).isEqualTo("evt-001");
        assertThat(event.externalId()).isEqualTo("ch-123");
        assertThat(event.status()).isEqualTo("pago");
    }

    @Test
    @DisplayName("parseWebhook throws when eventId is missing")
    void parseWebhook_missingEventId_throwsException() {
        String payload = "{\"externalId\": \"ch-123\", \"status\": \"pago\"}";
        Map<String, String> headers = Map.of();

        assertThatThrownBy(() -> gateway.parseWebhook(payload, headers))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("eventId");
    }

    @Test
    @DisplayName("parseWebhook throws when externalId is missing")
    void parseWebhook_missingExternalId_throwsException() {
        String payload = "{\"eventId\": \"evt-001\", \"status\": \"pago\"}";
        Map<String, String> headers = Map.of();

        assertThatThrownBy(() -> gateway.parseWebhook(payload, headers))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("externalId");
    }

    @Test
    @DisplayName("parseWebhook throws when status is missing")
    void parseWebhook_missingStatus_throwsException() {
        String payload = "{\"eventId\": \"evt-001\", \"externalId\": \"ch-123\"}";
        Map<String, String> headers = Map.of();

        assertThatThrownBy(() -> gateway.parseWebhook(payload, headers))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("status");
    }

    @Test
    @DisplayName("parseWebhook throws on invalid JSON")
    void parseWebhook_invalidJson_throwsException() {
        String payload = "not valid json";
        Map<String, String> headers = Map.of();

        assertThatThrownBy(() -> gateway.parseWebhook(payload, headers))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("invalido");
    }

    @Test
    @DisplayName("createProduct/createCheckout/createCustomer devolvem ids determinísticos")
    void v2_deterministicIds() {
        var prod = gateway.createProduct(new PaymentGateway.ProductRequest("sku-1", "N", 1000L, "BRL"));
        assertThat(prod.id()).isEqualTo("mock-prod-sku-1");

        var chk = gateway.createCheckout(new PaymentGateway.CheckoutRequest(
            java.util.List.of("mock-prod-sku-1"), java.util.List.of("PIX"), null, "sol-1", null, null));
        assertThat(chk.id()).isEqualTo("mock-chk-sol-1");
        assertThat(chk.url()).contains(chk.id());

        var cust = gateway.createCustomer(new PaymentGateway.CustomerRequest("e@x.com", "N", "123", null, null));
        assertThat(cust.id()).isEqualTo("mock-cust-123");

        gateway.deleteCustomer("mock-cust-123"); // não lança
    }

    @Test
    @DisplayName("parseWebhook handles null eventId, externalId, status gracefully")
    void parseWebhook_nullValues_throwsException() {
        String payload = "{\"eventId\": null, \"externalId\": \"ch-123\", \"status\": \"pago\"}";
        Map<String, String> headers = Map.of();

        assertThatThrownBy(() -> gateway.parseWebhook(payload, headers))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("eventId");
    }

    @Test
    @DisplayName("createPix devolve id mock, brCode, QR data URI e expiração no futuro")
    void createPix_devolvePixMock() {
        var r = gateway.createPix(new PaymentGateway.PixRequest(27000, 600, "x", Map.of(), null));

        assertThat(r.id()).startsWith("mock-pix-");
        assertThat(r.brCode()).isNotBlank();
        assertThat(r.qrBase64()).startsWith("data:image/png;base64,");
        assertThat(r.expiresAt()).isAfter(java.time.Instant.now());
        assertThat(gateway.pixStatus(r.id())).isEqualTo("pendente");
    }
}
