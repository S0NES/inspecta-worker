package br.com.vistoria.worker.metrics;

import br.com.vistoria.core.modules.metrics.MetricsRollupService;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Rollup diario das metricas de envio/solicitacoes (metrics.*_diario), lido pelo inspecta-api.
 *
 * <p>Reprocessa D-1 ate D-N ({@code inspecta.metrics.rollup.dias-retroativos}, default 7) todo dia
 * as 06:30 para capturar webhooks de provedor que chegaram atrasados. Cada
 * {@link MetricsRollupService#rolarDia(LocalDate)} e idempotente (upserts nativos), entao
 * reprocessar dias ja consolidados apenas recalcula os mesmos numeros.</p>
 *
 * <p>Idioma @Scheduled + @SchedulerLock, igual aos demais crons de background.</p>
 */
@Component
@Slf4j
public class MetricsRollupCron {

    private final MetricsRollupService rollupService;
    private final int diasRetroativos;

    public MetricsRollupCron(MetricsRollupService rollupService,
                             @Value("${inspecta.metrics.rollup.dias-retroativos:7}") int diasRetroativos) {
        this.rollupService = rollupService;
        this.diasRetroativos = diasRetroativos;
    }

    @Scheduled(cron = "0 30 6 * * *")
    @SchedulerLock(name = "metricsRollup", lockAtMostFor = "30m")
    @Transactional
    public void rolar() {
        LocalDate hoje = LocalDate.now();
        for (int i = 1; i <= diasRetroativos; i++) {
            rollupService.rolarDia(hoje.minusDays(i));
        }
        log.info("[metrics] rollup reprocessou D-1 ate D-{}", diasRetroativos);
    }
}
