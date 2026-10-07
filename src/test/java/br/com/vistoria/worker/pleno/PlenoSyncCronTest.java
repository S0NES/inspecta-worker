package br.com.vistoria.worker.pleno;

import br.com.vistoria.core.modules.integracao.IntegracaoSyncService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link PlenoSyncCron}.
 *
 * <p>Previously at 0% coverage — the poller that drains the outbound Pleno sync queue on a
 * fixed schedule had no test at all. Covers:
 * - delegation to {@link IntegracaoSyncService#drenarPendentes()}
 * - the {@code @Scheduled} interval is read from {@code inspecta.pleno.poll-ms} (default 60s)
 * - the {@code @SchedulerLock} name/lockAtMostFor, which is what actually prevents two
 *   inspecta-worker instances from draining the same backlog concurrently in production
 * - that a failure in the service propagates (so Spring's scheduler logs it) rather than
 *   being silently swallowed by the cron method
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlenoSyncCron — outbound Pleno sync poller")
class PlenoSyncCronTest {

    @Mock
    private IntegracaoSyncService service;

    @Test
    @DisplayName("drenar() delegates straight to IntegracaoSyncService.drenarPendentes()")
    void drenar_delegatesToService() {
        PlenoSyncCron cron = new PlenoSyncCron(service);

        cron.drenar();

        verify(service).drenarPendentes();
    }

    @Test
    @DisplayName("drenar() does not swallow exceptions from the service")
    void drenar_propagatesServiceFailure() {
        PlenoSyncCron cron = new PlenoSyncCron(service);
        doThrow(new RuntimeException("pleno indisponivel")).when(service).drenarPendentes();

        assertThatThrownBy(cron::drenar)
                .isInstanceOf(RuntimeException.class)
                .hasMessage("pleno indisponivel");
    }

    @Test
    @DisplayName("drenar() is scheduled every inspecta.pleno.poll-ms (default 60000ms)")
    void drenar_isScheduledWithConfigurablePollInterval() throws NoSuchMethodException {
        Method drenar = PlenoSyncCron.class.getDeclaredMethod("drenar");

        Scheduled scheduled = drenar.getAnnotation(Scheduled.class);
        assertThat(scheduled).as("drenar() should be annotated with @Scheduled").isNotNull();
        assertThat(scheduled.fixedDelayString()).isEqualTo("${inspecta.pleno.poll-ms:60000}");
    }

    @Test
    @DisplayName("drenar() holds a ShedLock so two instances never drain concurrently")
    void drenar_isProtectedBySchedulerLock() throws NoSuchMethodException {
        Method drenar = PlenoSyncCron.class.getDeclaredMethod("drenar");

        SchedulerLock lock = drenar.getAnnotation(SchedulerLock.class);
        assertThat(lock).as("drenar() should be annotated with @SchedulerLock").isNotNull();
        assertThat(lock.name()).isEqualTo("plenoSyncPoller");
        assertThat(lock.lockAtMostFor()).isEqualTo("5m");
    }
}
