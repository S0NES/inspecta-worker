package br.com.vistoria.worker.metrics;

import br.com.vistoria.core.events.EventTypes;
import br.com.vistoria.core.modules.metrics.MetricsService;
import br.com.vistoria.core.modules.metrics.StatusEnvio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link MetricsConsumer}: one routing key per case, asserting the parsed
 * envelope maps to the right {@link MetricsService} call. Unknown routing keys must be a
 * no-op that never throws (the {@code notify.requested.#} binding also delivers events with
 * no metrics meaning).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MetricsConsumer — routing key switch")
class MetricsConsumerTest {

    @Mock private MetricsService metricsService;

    private MetricsConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new MetricsConsumer(metricsService);
    }

    @Test
    @DisplayName("notify.requested.* -> registrarCriacao(eventId, canal, tipoComunicacao, ocorridoEm)")
    void notifyRequested_registersCriacao() {
        String body = """
                {"eventId":"evt-1","canal":"email","tipoComunicacao":"conta","ocorridoEm":"2026-08-20T10:15:30Z"}""";

        consumer.on(msg(body), "notify.requested.password-reset-code");

        verify(metricsService).registrarCriacao("evt-1", "email", "conta", Instant.parse("2026-08-20T10:15:30Z"));
    }

    @Test
    @DisplayName("notify.requested.* with missing fields passes nulls (inspecta-core applies defaults)")
    void notifyRequested_missingFields_passesNulls() {
        consumer.on(msg("{\"foo\":\"bar\"}"), "notify.requested.user-invited");

        verify(metricsService).registrarCriacao(isNull(), isNull(), isNull(), any(Instant.class));
    }

    @Test
    @DisplayName("jobs.metrics.enviado -> registrarEnvio(eventId, externalId, canal, ocorridoEm)")
    void metricsEnviado_registersEnvio() {
        String body = """
                {"eventId":"evt-2","externalId":"ext-9","canal":"sms","ocorridoEm":"2026-08-21T08:00:00Z"}""";

        consumer.on(msg(body), EventTypes.METRICS_ENVIADO);

        verify(metricsService).registrarEnvio("evt-2", "ext-9", "sms", Instant.parse("2026-08-21T08:00:00Z"));
    }

    @Test
    @DisplayName("jobs.metrics.status -> registrarStatus(broker, externalId, StatusEnvio, statusRaw, ocorridoEm)")
    void metricsStatus_registersStatus() {
        String body = """
                {"broker":"resend","externalId":"ext-9","status":"ENTREGUE","statusRaw":"email.delivered","ocorridoEm":"2026-08-22T09:30:00Z"}""";

        consumer.on(msg(body), EventTypes.METRICS_STATUS);

        verify(metricsService).registrarStatus(
                "resend", "ext-9", StatusEnvio.ENTREGUE, "email.delivered",
                Instant.parse("2026-08-22T09:30:00Z"));
    }

    @Test
    @DisplayName("unknown routing key -> no interaction, no throw")
    void unknownRoutingKey_isNoOp() {
        assertThatCode(() -> consumer.on(msg("{\"eventId\":\"evt-x\"}"), "jobs.metrics.qualquer-outra"))
                .doesNotThrowAnyException();

        verifyNoInteractions(metricsService);
    }

    @Test
    @DisplayName("missing ocorridoEm falls back to now()")
    void missingOcorridoEm_fallsBackToNow() {
        Instant before = Instant.now();
        consumer.on(msg("{\"eventId\":\"evt-3\",\"externalId\":\"e\"}"), EventTypes.METRICS_ENVIADO);

        var captor = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(metricsService).registrarEnvio(eq("evt-3"), eq("e"), isNull(), captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue())
                .isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
    }

    private static Message msg(String json) {
        MessageProperties props = new MessageProperties();
        return new Message(json.getBytes(), props);
    }
}
