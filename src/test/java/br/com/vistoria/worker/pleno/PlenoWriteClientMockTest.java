package br.com.vistoria.worker.pleno;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PlenoWriteClientMock")
class PlenoWriteClientMockTest {

    private PlenoWriteClientMock client;

    @BeforeEach
    void setUp() {
        client = new PlenoWriteClientMock();
    }

    @Test
    @DisplayName("espelhar returns mock external ID with resource type and ID")
    void espelhar_validInput_returnsMockResult() {
        UUID id = UUID.randomUUID();
        PlenoWriteClient.WriteResult result = client.espelhar("solicitacao", id);

        assertThat(result.externalId()).isEqualTo("mock-solicitacao-" + id);
    }

    @Test
    @DisplayName("espelhar handles different resource types")
    void espelhar_differentResourceTypes() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        UUID id3 = UUID.randomUUID();
        assertThat(client.espelhar("solicitacao", id1).externalId())
            .isEqualTo("mock-solicitacao-" + id1);
        assertThat(client.espelhar("laudo", id2).externalId())
            .isEqualTo("mock-laudo-" + id2);
        assertThat(client.espelhar("vistoria", id3).externalId())
            .isEqualTo("mock-vistoria-" + id3);
    }

    @Test
    @DisplayName("espelhar handles large resource IDs")
    void espelhar_largeResourceId() {
        UUID id = UUID.randomUUID();
        PlenoWriteClient.WriteResult result = client.espelhar("solicitacao", id);

        assertThat(result.externalId()).isEqualTo("mock-solicitacao-" + id);
    }

    @Test
    @DisplayName("espelhar generates unique IDs for different calls")
    void espelhar_differentCalls_generateDifferentIds() {
        PlenoWriteClient.WriteResult result1 = client.espelhar("solicitacao", UUID.randomUUID());
        PlenoWriteClient.WriteResult result2 = client.espelhar("solicitacao", UUID.randomUUID());

        assertThat(result1.externalId()).isNotEqualTo(result2.externalId());
    }

    @Test
    @DisplayName("syncStatus always returns 'sincronizado'")
    void syncStatus_anyInput_returnsSincronizado() {
        PlenoWriteClient.StatusResult result = client.syncStatus("solicitacao", UUID.randomUUID());

        assertThat(result.status()).isEqualTo("sincronizado");
        assertThat(result.erro()).isNull();
    }

    @Test
    @DisplayName("syncStatus returns no error for any resource type")
    void syncStatus_noError() {
        PlenoWriteClient.StatusResult result = client.syncStatus("laudo", UUID.randomUUID());

        assertThat(result.status()).isEqualTo("sincronizado");
        assertThat(result.erro()).isNull();
    }

    @Test
    @DisplayName("syncStatus handles various resource types")
    void syncStatus_variousResourceTypes() {
        assertThat(client.syncStatus("solicitacao", UUID.randomUUID()).status())
            .isEqualTo("sincronizado");
        assertThat(client.syncStatus("vistoria", UUID.randomUUID()).status())
            .isEqualTo("sincronizado");
        assertThat(client.syncStatus("atribuicao", UUID.randomUUID()).status())
            .isEqualTo("sincronizado");
    }

    @Test
    @DisplayName("syncStatus always succeeds without errors")
    void syncStatus_alwaysSucceeds() {
        for (int i = 0; i < 10; i++) {
            PlenoWriteClient.StatusResult result = client.syncStatus("test", UUID.randomUUID());
            assertThat(result.status()).isEqualTo("sincronizado");
            assertThat(result.erro()).isNull();
        }
    }

    @Test
    @DisplayName("both methods work without exceptions")
    void bothMethods_noExceptions() {
        client.espelhar("solicitacao", UUID.randomUUID());
        client.syncStatus("solicitacao", UUID.randomUUID());
        client.espelhar("laudo", UUID.randomUUID());
        client.syncStatus("laudo", UUID.randomUUID());
    }
}
