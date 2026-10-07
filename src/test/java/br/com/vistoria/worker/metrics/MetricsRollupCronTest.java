package br.com.vistoria.worker.metrics;

import br.com.vistoria.core.modules.metrics.MetricsRollupService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;

/**
 * Unit tests for {@link MetricsRollupCron}: verifies {@code rolarDia} is invoked once per
 * retro day, D-1 down to D-N, and never for today or the future.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MetricsRollupCron — reprocessa D-1..D-N")
class MetricsRollupCronTest {

    @Mock private MetricsRollupService rollupService;

    @Test
    @DisplayName("default 7 dias: rolarDia chamado 7x com D-1..D-7")
    void rolar_defaultSeteDias() {
        MetricsRollupCron cron = new MetricsRollupCron(rollupService, 7);
        LocalDate hoje = LocalDate.now();

        cron.rolar();

        ArgumentCaptor<LocalDate> captor = ArgumentCaptor.forClass(LocalDate.class);
        verify(rollupService, times(7)).rolarDia(captor.capture());

        List<LocalDate> esperados = IntStream.rangeClosed(1, 7)
                .mapToObj(hoje::minusDays)
                .toList();
        assertThat(captor.getAllValues()).containsExactlyElementsOf(esperados);
        assertThat(captor.getAllValues()).doesNotContain(hoje);
    }

    @Test
    @DisplayName("dias-retroativos configuravel: 3 -> D-1..D-3")
    void rolar_tresDias() {
        MetricsRollupCron cron = new MetricsRollupCron(rollupService, 3);
        LocalDate hoje = LocalDate.now();

        cron.rolar();

        verify(rollupService).rolarDia(hoje.minusDays(1));
        verify(rollupService).rolarDia(hoje.minusDays(2));
        verify(rollupService).rolarDia(hoje.minusDays(3));
        verify(rollupService, times(3)).rolarDia(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("dias-retroativos 0 -> nenhuma chamada")
    void rolar_zeroDias_noOp() {
        MetricsRollupCron cron = new MetricsRollupCron(rollupService, 0);

        cron.rolar();

        verifyNoInteractions(rollupService);
    }
}
