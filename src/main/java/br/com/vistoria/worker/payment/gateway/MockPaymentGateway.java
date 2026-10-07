package br.com.vistoria.worker.payment.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Component("mock")
@RequiredArgsConstructor
@Slf4j
public class MockPaymentGateway implements PaymentGateway {

    private final ObjectMapper mapper;

    @Override
    public String nome() { return "mock"; }

    @Override
    public ChargeResult createCharge(BigDecimal valor, Map<String, Object> metadata) {
        String externalId = "mock-" + UUID.randomUUID();
        log.info("[payment-mock] cobranca criada externalId={} valor={} metadata={}", externalId, valor, metadata);
        return new ChargeResult(externalId, "http://localhost:8080/mock-checkout/" + externalId);
    }

    /** PNG 1x1 — o mock não desenha QR; só precisa de uma data URI válida para a UI. */
    private static final String QR_PLACEHOLDER =
            "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    @Override
    public PixResult createPix(PixRequest request) {
        String id = "mock-pix-" + UUID.randomUUID();
        log.info("[payment-mock] PIX criado id={} amountCents={} metadata={}", id, request.amountCents(), request.metadata());
        return new PixResult(id, "00020126MOCK" + id + "6304ABCD", QR_PLACEHOLDER,
                java.time.Instant.now().plusSeconds(request.expiresInSeconds()));
    }

    /** Mock: PIX só vira "pago" pelo webhook mock (POST /v1/webhooks/pagamento/mock). */
    @Override
    public String pixStatus(String pixId) {
        return "pendente";
    }

    @Override
    public String getStatus(String externalId) {
        return "pendente";
    }

    @Override
    public void refund(String externalId) {
        log.info("[payment-mock] estorno solicitado externalId={}", externalId);
    }

    @Override
    public ProductResult createProduct(ProductRequest request) {
        String id = "mock-prod-" + request.externalId();
        log.info("[payment-mock] produto criado id={} name={} priceCents={}", id, request.name(), request.priceCents());
        return new ProductResult(id, request.externalId());
    }

    @Override
    public CheckoutResult createCheckout(CheckoutRequest request) {
        String id = "mock-chk-" + (request.externalId() == null ? UUID.randomUUID() : request.externalId());
        String url = "http://localhost:8080/mock-checkout/" + id;
        log.info("[payment-mock] checkout criado id={} produtos={} methods={} customerId={}",
                id, request.productIds(), request.methods(), request.customerId());
        return new CheckoutResult(id, url);
    }

    @Override
    public CustomerResult createCustomer(CustomerRequest request) {
        String taxId = request.taxId() == null || request.taxId().isBlank() ? "sem-taxid" : request.taxId();
        String id = "mock-cust-" + taxId;
        log.info("[payment-mock] customer criado id={} email={}", id, request.email());
        return new CustomerResult(id);
    }

    @Override
    public void deleteCustomer(String customerId) {
        log.info("[payment-mock] customer removido id={}", customerId);
    }

    @Override
    public PaymentEvent parseWebhook(String payload, Map<String, String> headers) {
        try {
            JsonNode json = mapper.readTree(payload);
            String eventId = json.path("eventId").asText(null);
            String externalId = json.path("externalId").asText(null);
            String status = json.path("status").asText(null);
            if (eventId == null || externalId == null || status == null)
                throw new IllegalArgumentException("payload mock exige eventId, externalId e status");
            return new PaymentEvent(eventId, externalId, status);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("payload de webhook mock invalido", e);
        }
    }
}
