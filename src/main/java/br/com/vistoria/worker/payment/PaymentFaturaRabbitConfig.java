package br.com.vistoria.worker.payment;

import br.com.vistoria.core.events.EventTypes;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * Fila dedicada do billing B2B por fatura consolidada ({@code plano-b2b-billing.md}).
 *
 * <p>{@code q.payment} (do {@code PaymentConsumer}) ja captura {@code jobs.payment.#}. Um segundo
 * {@code @RabbitListener} na mesma fila seria consumidor concorrente — a mensagem iria pra so um
 * dos dois. Por isso a fatura ganha fila propria, {@code q.payment.fatura}, com duas bindings:</p>
 * <ul>
 *   <li>{@code jobs.payment.fatura-charge-requested} — so o {@link FaturaCheckoutConsumer} escuta;</li>
 *   <li>{@code jobs.payment.webhook-received} — o webhook de pagamento e unico p/ toda a conta
 *       AbacatePay; o topic exchange entrega a MESMA mensagem pra {@code q.payment} e
 *       {@code q.payment.fatura}. O {@link FaturaCheckoutConsumer} so age quando
 *       {@code externalId} tem prefixo {@code "fatura-"}; o {@code PaymentConsumer} ja ignora
 *       com segurança o que nao casa com nenhuma {@code Pagamento} ("sem cobranca correspondente").</li>
 * </ul>
 */
@Configuration
public class PaymentFaturaRabbitConfig {

    public static final String QUEUE = "q.payment.fatura";

    @Bean
    public Declarables qPaymentFatura(TopicExchange eventsExchange) {
        Queue dlq = QueueBuilder.durable(QUEUE + ".dlq").build();
        Queue queue = QueueBuilder.durable(QUEUE)
                .withArguments(Map.of(
                        "x-dead-letter-exchange", "",
                        "x-dead-letter-routing-key", QUEUE + ".dlq"))
                .build();
        return new Declarables(List.<Declarable>of(
                dlq,
                queue,
                BindingBuilder.bind(queue).to(eventsExchange).with(EventTypes.PAYMENT_FATURA_CHARGE_REQUESTED),
                BindingBuilder.bind(queue).to(eventsExchange).with(EventTypes.PAYMENT_WEBHOOK)));
    }
}
