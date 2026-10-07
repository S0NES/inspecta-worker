package br.com.vistoria.worker.assignment;

import br.com.vistoria.worker.shared.Eventos;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class AssignmentConsumer {

    private final AssignmentService assignment;

    @RabbitListener(queues = "q.assignment", containerFactory = "assignmentListenerFactory")
    public void on(Message message) {
        JsonNode e = Eventos.parse(message.getBody());
        UUID servicoId = UUID.fromString(e.path("servicoId").asText());
        UUID excluir = e.hasNonNull("excluirVistoriadorId")
                ? UUID.fromString(e.path("excluirVistoriadorId").asText()) : null;
        String criadoPor = e.path("criadoPor").asText("sistema");
        String motivo = e.hasNonNull("motivo") ? e.path("motivo").asText() : null;
        var r = assignment.processar(servicoId, excluir, criadoPor, motivo);
        log.info("assignment servico={} atribuido={}", servicoId, r.atribuido());
    }
}
