package br.com.vistoria.worker.payment.gateway;

import br.com.vistoria.core.gateway.HttpGateway;
import br.com.vistoria.core.gateway.HttpGatewayRequest;
import br.com.vistoria.core.gateway.HttpGatewayResponse;

public abstract class AbstractHttpPaymentGateway implements PaymentGateway {

    protected final HttpGateway httpGateway;

    protected AbstractHttpPaymentGateway(HttpGateway httpGateway) {
        this.httpGateway = httpGateway;
    }

    protected HttpGatewayResponse chamar(HttpGatewayRequest request) throws Exception {
        return httpGateway.execute(request);
    }
}
