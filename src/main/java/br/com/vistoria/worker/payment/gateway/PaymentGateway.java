package br.com.vistoria.worker.payment.gateway;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface PaymentGateway {

    String nome();

    ChargeResult createCharge(BigDecimal valor, Map<String, Object> metadata);

    String getStatus(String externalId);

    void refund(String externalId);

    PaymentEvent parseWebhook(String payload, Map<String, String> headers);


    /** Cria um produto no catálogo do provider. {@code externalId} = SKU nosso (productId). */
    ProductResult createProduct(ProductRequest request);

    /** Cria um checkout hospedado com N produtos. Retorna id + URL para redirecionar/embutir. */
    CheckoutResult createCheckout(CheckoutRequest request);

    /** Cria (ou retorna, se o taxId já existir) um customer. */
    CustomerResult createCustomer(CustomerRequest request);

    /** Remove um customer pelo id do provider. */
    void deleteCustomer(String customerId);


    /** Gera um PIX. Provedores sem suporte lançam {@link UnsupportedOperationException}. */
    default PixResult createPix(PixRequest request) {
        throw new UnsupportedOperationException("provider " + nome() + " nao suporta PIX transparente");
    }

    /**
     * Status do PIX no provedor normalizado para o vocabulário do {@code Pagamento}:
     * {@code "pago"}, {@code "expirado"} ou {@code "pendente"}.
     */
    default String pixStatus(String pixId) {
        throw new UnsupportedOperationException("provider " + nome() + " nao suporta PIX transparente");
    }

    /** {@code customer} opcional (no AbacatePay, se informado todos os campos são obrigatórios). */
    record PixRequest(long amountCents, int expiresInSeconds, String description,
                      Map<String, String> metadata, CustomerRequest customer) {}
    /** {@code qrBase64} = data URI PNG pronto para {@code <img src>}. */
    record PixResult(String id, String brCode, String qrBase64, java.time.Instant expiresAt) {}

    record ChargeResult(String externalId, String checkoutUrl) {}

    record PaymentEvent(String eventId, String externalId, String status) {}

    /** {@code priceCents} em centavos; {@code currency} sempre {@code "BRL"}. */
    record ProductRequest(String externalId, String name, long priceCents, String currency) {}

    record ProductResult(String id, String externalId) {}

    /**
     * {@code productIds} = ids de produto no provider (data.id de cada createProduct);
     * {@code customerId} pode ser {@code null} (checkout sem customer pré-preenchido).
     */
    record CheckoutRequest(List<String> productIds, List<String> methods, String customerId,
                           String externalId, String returnUrl, String completionUrl) {}

    record CheckoutResult(String id, String url) {}

    record CustomerRequest(String email, String name, String taxId, String cellphone, String zipCode) {}

    record CustomerResult(String id) {}
}
