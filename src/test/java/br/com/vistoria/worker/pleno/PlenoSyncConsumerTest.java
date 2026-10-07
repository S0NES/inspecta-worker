package br.com.vistoria.worker.pleno;

import br.com.vistoria.core.modules.integracao.IntegracaoSyncService;
import br.com.vistoria.worker.shared.Eventos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PlenoSyncConsumer")
class PlenoSyncConsumerTest {

    @Mock private PlenoWriteClient mockClient;
    @Mock private PlenoWriteClient httpClient;
    @Mock private IntegracaoSyncService integracaoSync;

    private PlenoSyncConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new PlenoSyncConsumer(mockClient, httpClient, integracaoSync);
        ReflectionTestUtils.setField(consumer, "modo", "mock");
    }

    @Test
    @DisplayName("on espelhar calls client espelhar and applies result")
    void on_espelharAction_callsClientAndAppliesResult() {
        UUID syncId = UUID.randomUUID();
        UUID recursoId = UUID.randomUUID();
        String payload = "{\"integracaoSyncId\": \"" + syncId + "\", \"recursoTipo\": \"solicitacao\", "
            + "\"recursoId\": \"" + recursoId + "\", \"acao\": \"espelhar\"}";
        Message message = createMessage(payload);

        var espelharResult = new PlenoWriteClient.WriteResult("external-001");
        when(mockClient.espelhar("solicitacao", recursoId)).thenReturn(espelharResult);

        consumer.on(message);

        verify(mockClient).espelhar("solicitacao", recursoId);
        verify(integracaoSync).aplicarResultado(syncId, "sincronizado", "external-001", null);
    }

    @Test
    @DisplayName("on syncStatus calls client syncStatus and applies result")
    void on_syncStatusAction_callsClientAndAppliesResult() {
        UUID syncId = UUID.randomUUID();
        UUID recursoId = UUID.randomUUID();
        String payload = "{\"integracaoSyncId\": \"" + syncId + "\", \"recursoTipo\": \"laudo\", "
            + "\"recursoId\": \"" + recursoId + "\"}";
        Message message = createMessage(payload);

        var statusResult = new PlenoWriteClient.StatusResult("sincronizado", null);
        when(mockClient.syncStatus("laudo", recursoId)).thenReturn(statusResult);

        consumer.on(message);

        verify(mockClient).syncStatus("laudo", recursoId);
        verify(integracaoSync).aplicarResultado(syncId, "sincronizado", null, null);
    }

    @Test
    @DisplayName("on uses default action 'sync_status' when not provided")
    void on_noActionProvided_usesDefault() {
        UUID syncId = UUID.randomUUID();
        UUID recursoId = UUID.randomUUID();
        String payload = "{\"integracaoSyncId\": \"" + syncId + "\", \"recursoTipo\": \"vistoria\", "
            + "\"recursoId\": \"" + recursoId + "\"}";
        Message message = createMessage(payload);

        var statusResult = new PlenoWriteClient.StatusResult("pendente", null);
        when(mockClient.syncStatus("vistoria", recursoId)).thenReturn(statusResult);

        consumer.on(message);

        verify(mockClient).syncStatus("vistoria", recursoId);
    }

    @Test
    @DisplayName("on handles statusResult with error")
    void on_statusWithError_appliesError() {
        UUID syncId = UUID.randomUUID();
        UUID recursoId = UUID.randomUUID();
        String payload = "{\"integracaoSyncId\": \"" + syncId + "\", \"recursoTipo\": \"solicitacao\", "
            + "\"recursoId\": \"" + recursoId + "\"}";
        Message message = createMessage(payload);

        var statusResult = new PlenoWriteClient.StatusResult("erro", "Recurso não encontrado");
        when(mockClient.syncStatus("solicitacao", recursoId)).thenReturn(statusResult);

        consumer.on(message);

        verify(integracaoSync).aplicarResultado(syncId, "erro", null, "Recurso não encontrado");
    }

    @Test
    @DisplayName("on switches to http client when modo is 'http'")
    void on_httpMode_usesHttpClient() {
        ReflectionTestUtils.setField(consumer, "modo", "http");

        UUID syncId = UUID.randomUUID();
        UUID recursoId = UUID.randomUUID();
        String payload = "{\"integracaoSyncId\": \"" + syncId + "\", \"recursoTipo\": \"laudo\", "
            + "\"recursoId\": \"" + recursoId + "\"}";
        Message message = createMessage(payload);

        var statusResult = new PlenoWriteClient.StatusResult("sincronizado", null);
        when(httpClient.syncStatus("laudo", recursoId)).thenReturn(statusResult);

        consumer.on(message);

        verify(httpClient).syncStatus("laudo", recursoId);
    }

    @Test
    @DisplayName("on throws on invalid modo value")
    void on_invalidMode_throwsException() {
        ReflectionTestUtils.setField(consumer, "modo", "invalid");

        UUID syncId = UUID.randomUUID();
        UUID recursoId = UUID.randomUUID();
        String payload = "{\"integracaoSyncId\": \"" + syncId + "\", \"recursoTipo\": \"solicitacao\", "
            + "\"recursoId\": \"" + recursoId + "\"}";
        Message message = createMessage(payload);

        assertThatThrownBy(() -> consumer.on(message))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Invalid inspecta.pleno.mode");
    }

    @Test
    @DisplayName("on handles client exception and applies error")
    void on_clientThrowsException_appliesErrorAndRethrows() {
        UUID syncId = UUID.randomUUID();
        UUID recursoId = UUID.randomUUID();
        String payload = "{\"integracaoSyncId\": \"" + syncId + "\", \"recursoTipo\": \"vistoria\", "
            + "\"recursoId\": \"" + recursoId + "\"}";
        Message message = createMessage(payload);

        RuntimeException testException = new RuntimeException("Connection timeout");
        when(mockClient.syncStatus("vistoria", recursoId)).thenThrow(testException);

        assertThatThrownBy(() -> consumer.on(message))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("Connection timeout");

        verify(integracaoSync).aplicarResultado(syncId, "erro", null, "Connection timeout");
    }

    @Test
    @DisplayName("on handles espelhar with various resource types")
    void on_espelharVariousResources() {
        UUID syncId = UUID.randomUUID();
        UUID recursoId = UUID.randomUUID();
        String payload = "{\"integracaoSyncId\": \"" + syncId + "\", \"recursoTipo\": \"atribuicao\", "
            + "\"recursoId\": \"" + recursoId + "\", \"acao\": \"espelhar\"}";
        Message message = createMessage(payload);

        var result = new PlenoWriteClient.WriteResult("ext-456");
        when(mockClient.espelhar("atribuicao", recursoId)).thenReturn(result);

        consumer.on(message);

        verify(mockClient).espelhar("atribuicao", recursoId);
        verify(integracaoSync).aplicarResultado(syncId, "sincronizado", "ext-456", null);
    }

    @Test
    @DisplayName("on processes complex payload with all fields")
    void on_complexPayload() {
        UUID syncId = UUID.randomUUID();
        UUID recursoId = UUID.randomUUID();
        String payload = """
            {
              "integracaoSyncId": "%s",
              "recursoTipo": "solicitacao",
              "recursoId": "%s",
              "acao": "espelhar"
            }
            """.formatted(syncId, recursoId);
        Message message = createMessage(payload);

        var result = new PlenoWriteClient.WriteResult("ext-789");
        when(mockClient.espelhar("solicitacao", recursoId)).thenReturn(result);

        consumer.on(message);

        verify(integracaoSync).aplicarResultado(syncId, "sincronizado", "ext-789", null);
    }

    private Message createMessage(String payload) {
        byte[] body = payload.getBytes();
        MessageProperties props = new MessageProperties();
        props.setReceivedRoutingKey("jobs.pleno");
        return new Message(body, props);
    }
}
