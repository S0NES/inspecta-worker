package br.com.vistoria.worker.assignment;

import br.com.vistoria.worker.shared.Eventos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AssignmentConsumer")
class AssignmentConsumerTest {

    @Mock private AssignmentService assignment;

    private AssignmentConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new AssignmentConsumer(assignment);
    }

    @Test
    @DisplayName("on processes assignment message with servicoId")
    void on_validMessage_callsAssignmentService() {
        UUID servicoId = UUID.randomUUID();
        UUID vistoriadorId = UUID.randomUUID();
        String payload = "{\"servicoId\": \"" + servicoId + "\", \"criadoPor\": \"user123\"}";
        Message message = createMessage(payload);

        AssignmentService.Resultado resultado = new AssignmentService.Resultado(true, vistoriadorId, null);
        when(assignment.processar(servicoId, null, "user123", null)).thenReturn(resultado);

        assertThatCode(() -> consumer.on(message)).doesNotThrowAnyException();

        verify(assignment).processar(servicoId, null, "user123", null);
    }

    @Test
    @DisplayName("on processes assignment with excluirVistoriadorId")
    void on_withExcludeVistoriador_callsServiceWithExcludedId() {
        UUID servicoId = UUID.randomUUID();
        UUID excluirId = UUID.randomUUID();
        UUID vistoriadorId = UUID.randomUUID();
        String payload = "{\"servicoId\": \"" + servicoId + "\", \"excluirVistoriadorId\": \"" + excluirId
            + "\", \"criadoPor\": \"rebalance\"}";
        Message message = createMessage(payload);

        AssignmentService.Resultado resultado = new AssignmentService.Resultado(true, vistoriadorId, null);
        when(assignment.processar(servicoId, excluirId, "rebalance", null)).thenReturn(resultado);

        consumer.on(message);

        verify(assignment).processar(servicoId, excluirId, "rebalance", null);
    }

    @Test
    @DisplayName("on processes assignment with motivo")
    void on_withMotivo_callsServiceWithMotivo() {
        UUID servicoId = UUID.randomUUID();
        String payload = "{\"servicoId\": \"" + servicoId
            + "\", \"criadoPor\": \"sistema\", \"motivo\": \"Vistoriador indisponível\"}";
        Message message = createMessage(payload);

        AssignmentService.Resultado resultado = new AssignmentService.Resultado(false, null, null);
        when(assignment.processar(servicoId, null, "sistema", "Vistoriador indisponível"))
            .thenReturn(resultado);

        consumer.on(message);

        verify(assignment).processar(servicoId, null, "sistema", "Vistoriador indisponível");
    }

    @Test
    @DisplayName("on uses default criadoPor value 'sistema' when not provided")
    void on_criadoporNotProvided_usesDefault() {
        UUID servicoId = UUID.randomUUID();
        UUID vistoriadorId = UUID.randomUUID();
        String payload = "{\"servicoId\": \"" + servicoId + "\"}";
        Message message = createMessage(payload);

        AssignmentService.Resultado resultado = new AssignmentService.Resultado(true, vistoriadorId, null);
        when(assignment.processar(servicoId, null, "sistema", null)).thenReturn(resultado);

        consumer.on(message);

        verify(assignment).processar(servicoId, null, "sistema", null);
    }

    @Test
    @DisplayName("on handles assignment failure gracefully")
    void on_assignmentFails_logsResult() {
        UUID servicoId = UUID.randomUUID();
        String payload = "{\"servicoId\": \"" + servicoId + "\", \"criadoPor\": \"retry\"}";
        Message message = createMessage(payload);

        AssignmentService.Resultado resultado = new AssignmentService.Resultado(false, null, null);
        when(assignment.processar(servicoId, null, "retry", null)).thenReturn(resultado);

        consumer.on(message);

        verify(assignment).processar(servicoId, null, "retry", null);
    }

    @Test
    @DisplayName("on handles message with only servicoId")
    void on_minimalPayload_works() {
        UUID servicoId = UUID.randomUUID();
        UUID vistoriadorId = UUID.randomUUID();
        String payload = "{\"servicoId\": \"" + servicoId + "\"}";
        Message message = createMessage(payload);

        when(assignment.processar(servicoId, null, "sistema", null))
            .thenReturn(new AssignmentService.Resultado(true, vistoriadorId, null));

        assertThatCode(() -> consumer.on(message)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("on handles complex payload with all fields")
    void on_complexPayload_processesAllFields() {
        UUID servicoId = UUID.randomUUID();
        UUID excluirId = UUID.randomUUID();
        UUID vistoriadorId = UUID.randomUUID();
        String payload = """
            {
              "servicoId": "%s",
              "excluirVistoriadorId": "%s",
              "criadoPor": "balancer",
              "motivo": "Timeout em tentativa anterior"
            }
            """.formatted(servicoId, excluirId);
        Message message = createMessage(payload);

        AssignmentService.Resultado resultado = new AssignmentService.Resultado(true, vistoriadorId, null);
        when(assignment.processar(servicoId, excluirId, "balancer", "Timeout em tentativa anterior"))
            .thenReturn(resultado);

        consumer.on(message);

        verify(assignment).processar(servicoId, excluirId, "balancer", "Timeout em tentativa anterior");
    }

    private Message createMessage(String payload) {
        byte[] body = payload.getBytes();
        MessageProperties props = new MessageProperties();
        props.setReceivedRoutingKey("jobs.assignment");
        return new Message(body, props);
    }
}
