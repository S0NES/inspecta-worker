package br.com.vistoria.worker.assignment;

import br.com.vistoria.core.modules.atribuicao.AtribuicaoRepositories.FilaRepository;
import br.com.vistoria.core.modules.atribuicao.FilaAtribuicao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FilaAtribuicaoRetryCron")
class FilaAtribuicaoRetryCronTest {

    @Mock private FilaRepository fila;
    @Mock private AssignmentService assignment;

    private FilaAtribuicaoRetryCron cron;

    @BeforeEach
    void setUp() {
        cron = new FilaAtribuicaoRetryCron(fila, assignment);
    }

    @Test
    @DisplayName("retryFila finds items due for retry and processes them")
    void retryFila_itemsDue_processesAll() {
        UUID servico1 = UUID.randomUUID();
        UUID servico2 = UUID.randomUUID();

        FilaAtribuicao f1 = new FilaAtribuicao();
        f1.setServicoId(servico1);
        f1.setTentativas(1);

        FilaAtribuicao f2 = new FilaAtribuicao();
        f2.setServicoId(servico2);
        f2.setTentativas(2);

        when(fila.findByProximaTentativaBefore(any())).thenReturn(List.of(f1, f2));

        cron.retryFila();

        verify(assignment).processar(servico1, null, "sistema", null);
        verify(assignment).processar(servico2, null, "sistema", null);
    }

    @Test
    @DisplayName("retryFila uses 'sistema' as criadoPor")
    void retryFila_createdByIsSystema() {
        UUID servicoId = UUID.randomUUID();
        FilaAtribuicao f = new FilaAtribuicao();
        f.setServicoId(servicoId);
        when(fila.findByProximaTentativaBefore(any())).thenReturn(List.of(f));

        cron.retryFila();

        ArgumentCaptor<String> criadoFor = ArgumentCaptor.forClass(String.class);
        verify(assignment).processar(eq(servicoId), isNull(), criadoFor.capture(), isNull());
        assertThat(criadoFor.getValue()).isEqualTo("sistema");
    }

    @Test
    @DisplayName("retryFila handles empty queue gracefully")
    void retryFila_emptyQueue_noProcessing() {
        when(fila.findByProximaTentativaBefore(any())).thenReturn(List.of());

        cron.retryFila();

        verify(assignment, never()).processar(any(), any(), any(), any());
    }

    @Test
    @DisplayName("retryFila queries with current instant")
    void retryFila_usesCurrentInstant() {
        when(fila.findByProximaTentativaBefore(any())).thenReturn(List.of());

        cron.retryFila();

        ArgumentCaptor<Instant> instantCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(fila).findByProximaTentativaBefore(instantCaptor.capture());

        Instant capturedTime = instantCaptor.getValue();
        // Should be close to now (within 5 seconds)
        assertThat(capturedTime.getEpochSecond())
            .isCloseTo(Instant.now().getEpochSecond(), within(5L));
    }
}
