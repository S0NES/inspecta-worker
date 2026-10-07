package br.com.vistoria.worker.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.MaxAttemptsRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Configuration
public class JobsRabbitConfig {

    public static final String EXCHANGE = "inspecta.events";

    private Declarables fila(String nome, String pattern, TopicExchange ex) {
        return fila(nome, ex, pattern);
    }

    /**
     * Queue {@code nome} + its {@code nome.dlq}, bound to the topic exchange by one or more
     * routing-key patterns. {@code q.metrics} needs two bindings ({@code jobs.metrics.#} and
     * {@code notify.requested.#}) so it gets its own copy of the notify fan-out alongside
     * {@code q.notify} (inspecta-messenger), which is unaffected.
     */
    private Declarables fila(String nome, TopicExchange ex, String... patterns) {
        Queue dlq = QueueBuilder.durable(nome + ".dlq").build();
        Queue q = QueueBuilder.durable(nome)
                .withArguments(Map.of(
                        "x-dead-letter-exchange", "",
                        "x-dead-letter-routing-key", nome + ".dlq"))
                .build();
        List<Declarable> declarables = new ArrayList<>();
        declarables.add(dlq);
        declarables.add(q);
        for (String pattern : patterns) {
            declarables.add(BindingBuilder.bind(q).to(ex).with(pattern));
        }
        return new Declarables(declarables);
    }

    private RetryTemplate retryTemplate() {
        RetryTemplate rt = new RetryTemplate();
        rt.setRetryPolicy(new MaxAttemptsRetryPolicy(7));
        ExponentialBackOffPolicy backoff = new ExponentialBackOffPolicy();
        backoff.setInitialInterval(1000);      // 1 second
        backoff.setMultiplier(2);              // exponential: 1, 2, 4, 8, 16, 32, 64 seconds
        backoff.setMaxInterval(300000);        // cap at 5 minutes
        rt.setBackOffPolicy(backoff);
        return rt;
    }

    @Bean public Declarables qAssignment(TopicExchange ex) { return fila("q.assignment", "jobs.assignment.#", ex); }
    @Bean public Declarables qPdf(TopicExchange ex)        { return fila("q.pdf",        "jobs.pdf.#",        ex); }
    @Bean public Declarables qImage(TopicExchange ex)      { return fila("q.image",      "jobs.image.#",      ex); }
    @Bean public Declarables qPleno(TopicExchange ex)      { return fila("q.pleno",      "jobs.pleno.#",      ex); }
    // Só o preparo (jobs.signature.send). create-document e webhook-received sao do inspecta-messenger agora.
    @Bean public Declarables qSignature(TopicExchange ex)  { return fila("q.signature",  "jobs.signature.send",  ex); }
    @Bean public Declarables qPayment(TopicExchange ex)    { return fila("q.payment",    "jobs.payment.#",    ex); }

    /**
     * q.metrics <- jobs.metrics.# (enviado | status) AND notify.requested.# (criacao).
     * Two bindings: the topic exchange fans notify.requested.* out to both q.notify (inspecta-messenger)
     * and q.metrics independently.
     */
    @Bean public Declarables qMetrics(TopicExchange ex)    { return fila("q.metrics", ex, "jobs.metrics.#", "notify.requested.#"); }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory cf,
            SimpleRabbitListenerContainerFactoryConfigurer configurer) {
        SimpleRabbitListenerContainerFactory f = new SimpleRabbitListenerContainerFactory();
        configurer.configure(f, cf);
        // Explicit overrides after Boot's configurer: disable requeue on rejection (per-queue DLQ instead)
        f.setDefaultRequeueRejected(false);
        f.setPrefetchCount(1);
        return f;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory assignmentListenerFactory(
            ConnectionFactory cf,
            SimpleRabbitListenerContainerFactoryConfigurer configurer) {
        SimpleRabbitListenerContainerFactory f = new SimpleRabbitListenerContainerFactory();
        configurer.configure(f, cf);
        f.setDefaultRequeueRejected(false);
        f.setPrefetchCount(1);
        // Enable retry on q.assignment for transient integration failures
        // (e.g., Pleno read timeout, distance calc error). Service itself handles business retry via fila_atribuicao.
        f.setRetryTemplate(retryTemplate());
        return f;
    }

    /**
     * q.pdf consumers.
     *
     * <p>This factory used to be implicitly capped at one consumer because {@code PdfRenderer}
     * drove Playwright, whose Java API is not thread-safe. Since ADR-001 the renderer is
     * OpenHTML2PDF, holds no mutable state, and is safe to call concurrently, so the cap is
     * gone. Prefetch stays at 1 on purpose: rendering is a long job and fair inspecta-messenger matters
     * more than batching. Concurrency is configurable to keep the blast radius small if a
     * render turns out to be heavier than expected on the VPS.</p>
     */
    @Bean
    public SimpleRabbitListenerContainerFactory pdfListenerFactory(
            ConnectionFactory cf,
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            @Value("${inspecta.pdf.concurrency:2}") int concurrency) {
        SimpleRabbitListenerContainerFactory f = new SimpleRabbitListenerContainerFactory();
        configurer.configure(f, cf);
        f.setDefaultRequeueRejected(false);
        f.setPrefetchCount(1);
        f.setConcurrentConsumers(concurrency);
        // Enable retry on q.pdf for transient rendering/R2 failures. Replay guard ensures idempotency.
        f.setRetryTemplate(retryTemplate());
        return f;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory imageListenerFactory(
            ConnectionFactory cf,
            SimpleRabbitListenerContainerFactoryConfigurer configurer) {
        SimpleRabbitListenerContainerFactory f = new SimpleRabbitListenerContainerFactory();
        configurer.configure(f, cf);
        f.setDefaultRequeueRejected(false);
        f.setPrefetchCount(1);
        // Enable retry on q.image for transient R2/image processing failures.
        f.setRetryTemplate(retryTemplate());
        return f;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory plenoListenerFactory(
            ConnectionFactory cf,
            SimpleRabbitListenerContainerFactoryConfigurer configurer) {
        SimpleRabbitListenerContainerFactory f = new SimpleRabbitListenerContainerFactory();
        configurer.configure(f, cf);
        // Retry enabled on q.pleno. Higher concurrency for parallel Pleno writes.
        f.setDefaultRequeueRejected(false);
        f.setPrefetchCount(5);
        f.setConcurrentConsumers(5);
        f.setRetryTemplate(retryTemplate());
        return f;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory signatureListenerFactory(
            ConnectionFactory cf,
            SimpleRabbitListenerContainerFactoryConfigurer configurer) {
        SimpleRabbitListenerContainerFactory f = new SimpleRabbitListenerContainerFactory();
        configurer.configure(f, cf);
        // Retry enabled on q.signature. Replay guard (documentoJaConcluido) ensures idempotency.
        f.setDefaultRequeueRejected(false);
        f.setPrefetchCount(1);
        f.setRetryTemplate(retryTemplate());
        return f;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory paymentListenerFactory(
            ConnectionFactory cf,
            SimpleRabbitListenerContainerFactoryConfigurer configurer) {
        SimpleRabbitListenerContainerFactory f = new SimpleRabbitListenerContainerFactory();
        configurer.configure(f, cf);
        // Retry enabled on q.payment. Transient payment gateway failures warrant retry.
        f.setDefaultRequeueRejected(false);
        f.setPrefetchCount(1);
        f.setRetryTemplate(retryTemplate());
        return f;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory metricsListenerFactory(
            ConnectionFactory cf,
            SimpleRabbitListenerContainerFactoryConfigurer configurer) {
        SimpleRabbitListenerContainerFactory f = new SimpleRabbitListenerContainerFactory();
        configurer.configure(f, cf);
        // Retry enabled on q.metrics. MetricsService methods are all idempotent, so a transient
        // DB failure can be safely retried and only dead-letters after exhaustion.
        f.setDefaultRequeueRejected(false);
        f.setPrefetchCount(10);
        f.setRetryTemplate(retryTemplate());
        return f;
    }
}
