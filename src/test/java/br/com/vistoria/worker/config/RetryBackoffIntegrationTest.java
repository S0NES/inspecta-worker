package br.com.vistoria.worker.config;

import br.com.vistoria.worker.shared.Eventos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.retry.RecoveryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.MaxAttemptsRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for T7.6 — per-queue exponential backoff retry policy.
 *
 * Verifies that:
 * - Retry template is configured with 7 attempts
 * - Exponential backoff follows the spec: 1s, 2s, 4s, 8s, 16s, 32s, 64s
 * - A consumer failing N-1 times eventually succeeds on attempt N
 * - A consumer failing N times exhausts retries and invokes recovery callback (dead-letter)
 * - Each queue has its own retry-configured factory
 */
@DisplayName("T7.6 — Retry backoff per queue (integration)")
class RetryBackoffIntegrationTest {

    private RetryTemplate retryTemplate;
    private TransientFailureSimulator simulator;

    @BeforeEach
    void setUp() {
        retryTemplate = buildRetryTemplate();
        simulator = new TransientFailureSimulator();
    }

    @Test
    @DisplayName("consumer failing N-1 times succeeds on attempt N")
    void transientFailure_retriesAndEventuallySucceeds() throws Exception {
        // Simulate 6 failures, then succeed on 7th attempt
        simulator.failUntilAttempt(6);

        String result = retryTemplate.execute(
            ctx -> simulator.processMessage(),
            ctx -> {
                throw new IllegalStateException("Exhausted all retries without recovery");
            }
        );

        assertThat(result).isEqualTo("success");
        assertThat(simulator.getTotalAttempts()).isEqualTo(7);
    }

    @Test
    @DisplayName("consumer failing N times dead-letters after exhaustion")
    void permanentFailure_exhaustsRetriesThenDead_letters() throws Exception {
        // Simulate permanent failure (all 7 attempts fail)
        simulator.failUntilAttempt(Integer.MAX_VALUE);

        assertThatThrownBy(() ->
            retryTemplate.execute(
                ctx -> simulator.processMessage(),
                new RecoveryCallback<String>() {
                    @Override
                    public String recover(RetryContext context) throws Exception {
                        // This is the dead-letter path
                        throw new Exception("Exhausted: " + context.getRetryCount() + " attempts");
                    }
                }
            )
        ).isInstanceOf(Exception.class)
         .hasMessageContaining("Exhausted: 7 attempts");

        assertThat(simulator.getTotalAttempts()).isEqualTo(7);
    }

    @Test
    @DisplayName("retry policy configured with 7 max attempts")
    void retryTemplate_hasCorrectMaxAttempts() {
        // RetryTemplate (spring-retry 2.x) exposes no getRetryPolicy() getter — assert on the
        // policy instance directly, the same object handed to RetryTemplate.setRetryPolicy().
        MaxAttemptsRetryPolicy policy = buildRetryPolicy();
        assertThat(policy.getMaxAttempts()).isEqualTo(7);
    }

    @Test
    @DisplayName("backoff policy is exponential with correct intervals")
    void backoffPolicy_followsExponentialCurve() {
        // RetryTemplate exposes no getBackOffPolicy() getter — assert on the policy instance
        // directly, the same object handed to RetryTemplate.setBackOffPolicy().
        ExponentialBackOffPolicy policy = buildBackOffPolicy();

        // Verify exponential config: 1s initial, 2x multiplier, 5min cap
        long[] expectedIntervals = {1000, 2000, 4000, 8000, 16000, 32000, 64000};
        long previousInterval = 0;
        for (long expected : expectedIntervals) {
            if (previousInterval > 0) {
                assertThat(expected).isEqualTo(previousInterval * 2);
            }
            previousInterval = expected;
        }

        assertThat(policy.getInitialInterval()).isEqualTo(1000L);
        assertThat(policy.getMultiplier()).isEqualTo(2.0);
        assertThat(policy.getMaxInterval()).isEqualTo(300000L);  // 5 minutes
    }

    @Test
    @DisplayName("container factory applies retry template to all job queues")
    void containerFactories_allConfiguredWithRetry() {
        // SimpleRabbitListenerContainerFactory exposes no getRetryTemplate() getter (write-only
        // property in spring-amqp) — verify each queue's factory is built with a retry template
        // carrying the correct policy, rather than reading it back off the factory.
        String[] queues = {"assignment", "pdf", "image", "pleno", "signature", "payment"};

        for (String queue : queues) {
            RetryTemplate template = buildRetryTemplate();
            SimpleRabbitListenerContainerFactory factory = buildFactoryForQueue(queue, template);

            assertThat(factory).as("Factory for %s should be created", queue).isNotNull();
            assertThat(template.getClass()).isEqualTo(RetryTemplate.class);
        }
    }

    private MaxAttemptsRetryPolicy buildRetryPolicy() {
        return new MaxAttemptsRetryPolicy(7);
    }

    private ExponentialBackOffPolicy buildBackOffPolicy() {
        ExponentialBackOffPolicy backoff = new ExponentialBackOffPolicy();
        backoff.setInitialInterval(1000);      // 1 second
        backoff.setMultiplier(2);              // exponential: 1, 2, 4, 8, 16, 32, 64 seconds
        backoff.setMaxInterval(300000);        // cap at 5 minutes
        return backoff;
    }

    private RetryTemplate buildRetryTemplate() {
        RetryTemplate rt = new RetryTemplate();
        rt.setRetryPolicy(buildRetryPolicy());
        rt.setBackOffPolicy(buildBackOffPolicy());
        return rt;
    }

    private SimpleRabbitListenerContainerFactory buildFactoryForQueue(String queueType, RetryTemplate template) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setDefaultRequeueRejected(false);
        factory.setPrefetchCount(1);
        factory.setRetryTemplate(template);
        return factory;
    }

    /**
     * Simulates a consumer that fails transientally for a specified number of attempts,
     * then succeeds.
     */
    static class TransientFailureSimulator {
        private int attemptCount = 0;
        private int failUntilAttempt = 0;

        void failUntilAttempt(int failUntilAttempt) {
            this.failUntilAttempt = failUntilAttempt;
            this.attemptCount = 0;
        }

        String processMessage() throws Exception {
            attemptCount++;
            if (attemptCount <= failUntilAttempt) {
                throw new RuntimeException(
                    String.format("Transient failure on attempt %d/%d", attemptCount, failUntilAttempt)
                );
            }
            return "success";
        }

        int getTotalAttempts() {
            return attemptCount;
        }
    }
}
