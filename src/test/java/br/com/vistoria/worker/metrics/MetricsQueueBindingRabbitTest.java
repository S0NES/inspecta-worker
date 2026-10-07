package br.com.vistoria.worker.metrics;

import br.com.vistoria.worker.config.JobsRabbitConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the {@code q.metrics} wiring against a real broker: the queue must
 * receive an independent copy of BOTH {@code jobs.metrics.*} and {@code notify.requested.*}
 * routed to the {@code inspecta.events} topic exchange.
 *
 * <p>Skipped automatically when Docker is unavailable ({@code disabledWithoutDocker = true});
 * it runs in CI where Docker is present.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("q.metrics two-binding wiring (RabbitMQ integration)")
class MetricsQueueBindingRabbitTest {

    @Container
    static final RabbitMQContainer RABBIT =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"));

    @Test
    @DisplayName("q.metrics receives both jobs.metrics.# and notify.requested.# messages")
    void qMetrics_receivesBothRoutingKeys() {
        CachingConnectionFactory cf = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        cf.setUsername(RABBIT.getAdminUsername());
        cf.setPassword(RABBIT.getAdminPassword());
        try {
            RabbitAdmin admin = new RabbitAdmin(cf);
            TopicExchange exchange = new TopicExchange(JobsRabbitConfig.EXCHANGE);
            admin.declareExchange(exchange);

            Declarables declarables = new JobsRabbitConfig().qMetrics(exchange);
            for (Declarable d : declarables.getDeclarables()) {
                if (d instanceof org.springframework.amqp.core.Queue q) {
                    admin.declareQueue(q);
                } else if (d instanceof org.springframework.amqp.core.Binding b) {
                    admin.declareBinding(b);
                }
            }

            RabbitTemplate tpl = new RabbitTemplate(cf);
            tpl.convertAndSend(JobsRabbitConfig.EXCHANGE, "jobs.metrics.enviado", "{\"eventId\":\"e1\"}");
            tpl.convertAndSend(JobsRabbitConfig.EXCHANGE, "notify.requested.user-invited", "{\"eventId\":\"e2\"}");

            assertThat(admin.getQueueInfo("q.metrics")).isNotNull();
            // give the broker a beat to route
            org.awaitility.Awaitility.await()
                    .atMost(java.time.Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(
                            admin.getQueueInfo("q.metrics").getMessageCount()).isEqualTo(2));
        } finally {
            cf.destroy();
        }
    }
}
