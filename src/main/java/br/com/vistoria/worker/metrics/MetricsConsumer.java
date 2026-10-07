package br.com.vistoria.worker.metrics;

import br.com.vistoria.core.events.EventTypes;
import br.com.vistoria.core.modules.metrics.MetricsService;
import br.com.vistoria.core.modules.metrics.StatusEnvio;
import br.com.vistoria.worker.shared.Eventos;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * q.metrics <- jobs.metrics.# (enviado | status) e notify.requested.# (criacao).
 *
 * <p>q.notify (inspecta-messenger) e q.metrics recebem copias independentes de cada
 * {@code notify.requested.*} via o fan-out do topic exchange — o inspecta-messenger nao muda.</p>
 *
 * <p>Todos os metodos de {@link MetricsService} sao idempotentes, entao entrega at-least-once
 * e replay de evento sao seguros. Routing keys sem significado de metrica (o binding
 * {@code notify.requested.#} tambem entrega eventos que nao geram criacao) sao ignoradas com
 * WARN e ack — nunca lancam.</p>
 */
@Component
@Slf4j
public class MetricsConsumer {

    private final MetricsService metricsService;

    public MetricsConsumer(MetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @RabbitListener(queues = "q.metrics", containerFactory = "metricsListenerFactory")
    @Transactional
    public void on(Message message, @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey) {
        JsonNode e = Eventos.parse(message.getBody());
        String rk = routingKey == null ? "" : routingKey;

        if (rk.startsWith(EventTypes.NOTIFY_PREFIX)) {
            metricsService.registrarCriacao(
                    texto(e, "eventId"),
                    texto(e, "canal"),
                    texto(e, "tipoComunicacao"),
                    ocorridoEm(e));
            return;
        }

        switch (rk) {
            case EventTypes.METRICS_ENVIADO -> metricsService.registrarEnvio(
                    texto(e, "eventId"),
                    texto(e, "externalId"),
                    texto(e, "canal"),
                    ocorridoEm(e));
            case EventTypes.METRICS_STATUS -> metricsService.registrarStatus(
                    texto(e, "broker"),
                    texto(e, "externalId"),
                    StatusEnvio.valueOf(texto(e, "status")),
                    texto(e, "statusRaw"),
                    ocorridoEm(e));
            default -> log.warn("[metrics] routing key sem tratamento, ignorada: {}", rk);
        }
    }

    private static String texto(JsonNode e, String campo) {
        JsonNode n = e.get(campo);
        return n == null || n.isNull() ? null : n.asText();
    }

    private static Instant ocorridoEm(JsonNode e) {
        String v = texto(e, "ocorridoEm");
        if (v == null || v.isBlank()) {
            return Instant.now();
        }
        try {
            return Instant.parse(v);
        } catch (RuntimeException ex) {
            log.warn("[metrics] ocorridoEm invalido '{}', usando agora", v);
            return Instant.now();
        }
    }
}
