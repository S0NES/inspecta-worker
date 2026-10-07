package br.com.vistoria.worker.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.MaxAttemptsRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link JobsRabbitConfig}.
 *
 * <p>This class was previously at 0% coverage: {@code RetryBackoffIntegrationTest} verifies
 * spring-retry behaviour against a hand-rolled copy of the retry policy, not the real beans
 * produced by this configuration class. That leaves the actual queue/DLQ wiring and per-queue
 * container factory settings (T7.6 retry, T7.7 DLQ naming, pdf concurrency) completely
 * unverified — a typo in a routing key or a dropped {@code setRetryTemplate} call here would
 * silently break dead-lettering or retry in production with no test failing.
 *
 * <p>Every bean method here is a plain factory method (no field injection), so it can be
 * exercised directly without a Spring context: a mocked {@link ConnectionFactory} and a real
 * {@link SimpleRabbitListenerContainerFactoryConfigurer} (backed by default {@link RabbitProperties})
 * are enough. {@code SimpleRabbitListenerContainerFactory} exposes no getters for
 * prefetchCount/concurrentConsumers/retryTemplate (write-only, like spring-amqp's other
 * container factories), so assertions go through reflection on the underlying fields —
 * the same pattern already used in this package (see {@code DlqMonitorTest}).
 */
@DisplayName("JobsRabbitConfig — queue/DLQ wiring and per-queue container factories")
class JobsRabbitConfigTest {

    private JobsRabbitConfig config;
    private TopicExchange exchange;
    private ConnectionFactory connectionFactory;
    private SimpleRabbitListenerContainerFactoryConfigurer configurer;

    @BeforeEach
    void setUp() {
        config = new JobsRabbitConfig();
        exchange = new TopicExchange(JobsRabbitConfig.EXCHANGE);
        connectionFactory = mock(ConnectionFactory.class);
        configurer = new SimpleRabbitListenerContainerFactoryConfigurer(new RabbitProperties());
    }

    @Nested
    @DisplayName("queue + DLQ declarations")
    class QueueDeclarations {

        @ParameterizedTest(name = "{0} binds \"{1}\" on inspecta.events and dead-letters to {0}.dlq")
        @CsvSource({
                "q.assignment, jobs.assignment.#",
                "q.pdf,        jobs.pdf.#",
                "q.image,      jobs.image.#",
                "q.pleno,      jobs.pleno.#",
                "q.signature,  jobs.signature.send",
                "q.payment,    jobs.payment.#"
        })
        void queue_isDeclaredWithDlqAndBinding(String queueName, String pattern) {
            Declarables declarables = switch (queueName) {
                case "q.assignment" -> config.qAssignment(exchange);
                case "q.pdf" -> config.qPdf(exchange);
                case "q.image" -> config.qImage(exchange);
                case "q.pleno" -> config.qPleno(exchange);
                case "q.signature" -> config.qSignature(exchange);
                case "q.payment" -> config.qPayment(exchange);
                default -> throw new IllegalArgumentException(queueName);
            };

            Collection<Declarable> items = declarables.getDeclarables();
            assertThat(items).hasSize(3);

            Queue dlq = items.stream()
                    .filter(Queue.class::isInstance).map(Queue.class::cast)
                    .filter(q -> q.getName().equals(queueName + ".dlq"))
                    .findFirst().orElseThrow(() -> new AssertionError("DLQ not declared for " + queueName));
            assertThat(dlq.isDurable()).isTrue();

            Queue main = items.stream()
                    .filter(Queue.class::isInstance).map(Queue.class::cast)
                    .filter(q -> q.getName().equals(queueName))
                    .findFirst().orElseThrow(() -> new AssertionError("Main queue not declared for " + queueName));
            assertThat(main.isDurable()).isTrue();
            assertThat(main.getArguments())
                    .as("main queue must dead-letter into its own DLQ via default exchange routing")
                    .containsEntry("x-dead-letter-exchange", "")
                    .containsEntry("x-dead-letter-routing-key", queueName + ".dlq");

            Binding binding = items.stream()
                    .filter(Binding.class::isInstance).map(Binding.class::cast)
                    .findFirst().orElseThrow(() -> new AssertionError("Binding not declared for " + queueName));
            assertThat(binding.getDestination()).isEqualTo(queueName);
            assertThat(binding.getExchange()).isEqualTo(JobsRabbitConfig.EXCHANGE);
            assertThat(binding.getRoutingKey()).isEqualTo(pattern);
        }

        @Test
        @DisplayName("q.metrics is declared with its DLQ and TWO bindings: jobs.metrics.# and notify.requested.#")
        void qMetrics_hasDlqAndTwoBindings() {
            Declarables declarables = config.qMetrics(exchange);
            Collection<Declarable> items = declarables.getDeclarables();

            assertThat(items).hasSize(4); // dlq + main queue + 2 bindings

            Queue dlq = items.stream()
                    .filter(Queue.class::isInstance).map(Queue.class::cast)
                    .filter(q -> q.getName().equals("q.metrics.dlq"))
                    .findFirst().orElseThrow(() -> new AssertionError("q.metrics.dlq not declared"));
            assertThat(dlq.isDurable()).isTrue();

            Queue main = items.stream()
                    .filter(Queue.class::isInstance).map(Queue.class::cast)
                    .filter(q -> q.getName().equals("q.metrics"))
                    .findFirst().orElseThrow(() -> new AssertionError("q.metrics not declared"));
            assertThat(main.isDurable()).isTrue();
            assertThat(main.getArguments())
                    .containsEntry("x-dead-letter-exchange", "")
                    .containsEntry("x-dead-letter-routing-key", "q.metrics.dlq");

            assertThat(items.stream()
                    .filter(Binding.class::isInstance).map(Binding.class::cast)
                    .peek(b -> {
                        assertThat(b.getDestination()).isEqualTo("q.metrics");
                        assertThat(b.getExchange()).isEqualTo(JobsRabbitConfig.EXCHANGE);
                    })
                    .map(Binding::getRoutingKey))
                    .containsExactlyInAnyOrder("jobs.metrics.#", "notify.requested.#");
        }

        @Test
        @DisplayName("each queue's DLQ has no dead-letter arguments of its own (terminal, no further retry)")
        void dlq_hasNoDeadLetterArguments() {
            Declarables declarables = config.qPayment(exchange);
            Queue dlq = declarables.getDeclarables().stream()
                    .filter(Queue.class::isInstance).map(Queue.class::cast)
                    .filter(q -> q.getName().equals("q.payment.dlq"))
                    .findFirst().orElseThrow();

            assertThat(dlq.getArguments()).isNullOrEmpty();
        }
    }

    @Nested
    @DisplayName("retry-enabled container factories (T7.6)")
    class RetryEnabledFactories {

        @Test
        void assignmentListenerFactory_hasRetryAndNoRequeue() {
            SimpleRabbitListenerContainerFactory f = config.assignmentListenerFactory(connectionFactory, configurer);
            assertPrefetchAndRequeue(f, 1);
            assertRetryTemplateConfigured(f);
        }

        @Test
        void imageListenerFactory_hasRetryAndNoRequeue() {
            SimpleRabbitListenerContainerFactory f = config.imageListenerFactory(connectionFactory, configurer);
            assertPrefetchAndRequeue(f, 1);
            assertRetryTemplateConfigured(f);
        }

        @Test
        void signatureListenerFactory_hasRetryAndNoRequeue() {
            SimpleRabbitListenerContainerFactory f = config.signatureListenerFactory(connectionFactory, configurer);
            assertPrefetchAndRequeue(f, 1);
            assertRetryTemplateConfigured(f);
        }

        @Test
        void paymentListenerFactory_hasRetryAndNoRequeue() {
            SimpleRabbitListenerContainerFactory f = config.paymentListenerFactory(connectionFactory, configurer);
            assertPrefetchAndRequeue(f, 1);
            assertRetryTemplateConfigured(f);
        }

        @Test
        @DisplayName("metricsListenerFactory: prefetch 10, no requeue, retry template")
        void metricsListenerFactory_prefetchTenWithRetry() {
            SimpleRabbitListenerContainerFactory f = config.metricsListenerFactory(connectionFactory, configurer);
            assertPrefetchAndRequeue(f, 10);
            assertRetryTemplateConfigured(f);
        }

        @Test
        @DisplayName("plenoListenerFactory raises prefetch/concurrency to 5 for parallel Pleno writes")
        void plenoListenerFactory_hasHigherConcurrency() {
            SimpleRabbitListenerContainerFactory f = config.plenoListenerFactory(connectionFactory, configurer);
            assertPrefetchAndRequeue(f, 5);
            assertRetryTemplateConfigured(f);
            assertThat((Integer) ReflectionTestUtils.getField(f, "concurrentConsumers")).isEqualTo(5);
        }

        @Test
        @DisplayName("pdfListenerFactory keeps prefetch at 1 but wires concurrency from inspecta.pdf.concurrency")
        void pdfListenerFactory_prefetchStaysOneConcurrencyIsConfigurable() {
            SimpleRabbitListenerContainerFactory f = config.pdfListenerFactory(connectionFactory, configurer, 4);
            assertPrefetchAndRequeue(f, 1);
            assertRetryTemplateConfigured(f);
            assertThat((Integer) ReflectionTestUtils.getField(f, "concurrentConsumers")).isEqualTo(4);
        }

        @Test
        @DisplayName("pdfListenerFactory concurrency defaults to 2 when the property is absent")
        void pdfListenerFactory_defaultConcurrencyIsTwo() {
            // The @Value default ("${inspecta.pdf.concurrency:2}") only resolves through Spring's
            // property binding; calling the bean method directly (as this whole test class does,
            // deliberately avoiding a full ApplicationContext) means the caller must supply the
            // value. This test pins the documented default itself, so a change to "2" in
            // JobsRabbitConfig's @Value annotation is caught here even though the container
            // never runs.
            String defaultValue = extractPdfConcurrencyDefault();
            assertThat(defaultValue).isEqualTo("2");
        }

        private String extractPdfConcurrencyDefault() {
            try {
                var param = JobsRabbitConfig.class
                        .getDeclaredMethod("pdfListenerFactory", ConnectionFactory.class,
                                SimpleRabbitListenerContainerFactoryConfigurer.class, int.class)
                        .getParameters()[2];
                var value = param.getAnnotation(org.springframework.beans.factory.annotation.Value.class);
                String expr = value.value(); // "${inspecta.pdf.concurrency:2}"
                return expr.substring(expr.indexOf(':') + 1, expr.length() - 1);
            } catch (NoSuchMethodException e) {
                throw new AssertionError(e);
            }
        }

        private void assertPrefetchAndRequeue(SimpleRabbitListenerContainerFactory f, int expectedPrefetch) {
            assertThat((Integer) ReflectionTestUtils.getField(f, "prefetchCount")).isEqualTo(expectedPrefetch);
            assertThat((Boolean) ReflectionTestUtils.getField(f, "defaultRequeueRejected")).isFalse();
        }

        private void assertRetryTemplateConfigured(SimpleRabbitListenerContainerFactory f) {
            Object retryTemplate = ReflectionTestUtils.getField(f, "retryTemplate");
            assertThat(retryTemplate).as("retry template must be set for T7.6").isInstanceOf(RetryTemplate.class);

            Object retryPolicy = ReflectionTestUtils.getField(retryTemplate, "retryPolicy");
            assertThat(retryPolicy).isInstanceOf(MaxAttemptsRetryPolicy.class);
            assertThat(((MaxAttemptsRetryPolicy) retryPolicy).getMaxAttempts()).isEqualTo(7);

            Object backOffPolicy = ReflectionTestUtils.getField(retryTemplate, "backOffPolicy");
            assertThat(backOffPolicy).isInstanceOf(ExponentialBackOffPolicy.class);
            ExponentialBackOffPolicy backoff = (ExponentialBackOffPolicy) backOffPolicy;
            assertThat(backoff.getInitialInterval()).isEqualTo(1000L);
            assertThat(backoff.getMultiplier()).isEqualTo(2.0);
            assertThat(backoff.getMaxInterval()).isEqualTo(300000L);
        }
    }

    @Test
    @DisplayName("the plain rabbitListenerContainerFactory (no @RabbitListener currently names it) has no retry template")
    void baseContainerFactory_hasNoRetryTemplate() {
        // Nothing in src/main currently sets containerFactory = "rabbitListenerContainerFactory"
        // explicitly, but it is still Spring's default factory name and would be picked up by
        // any @RabbitListener that omits containerFactory. Unlike every job queue it must NOT
        // retry-wrap silently, since none of the DLQ/idempotency guarantees the queues below
        // were designed around apply to it.
        SimpleRabbitListenerContainerFactory f = config.rabbitListenerContainerFactory(connectionFactory, configurer);

        assertThat((Integer) ReflectionTestUtils.getField(f, "prefetchCount")).isEqualTo(1);
        assertThat((Boolean) ReflectionTestUtils.getField(f, "defaultRequeueRejected")).isFalse();
        assertThat(ReflectionTestUtils.getField(f, "retryTemplate")).isNull();
    }

    @Test
    @DisplayName("EXCHANGE constant matches the topic exchange used by every job queue binding")
    void exchangeConstant_isInspectaEvents() {
        assertThat(JobsRabbitConfig.EXCHANGE).isEqualTo("inspecta.events");
        assertThat(exchange.getName()).isEqualTo(JobsRabbitConfig.EXCHANGE);
    }
}
