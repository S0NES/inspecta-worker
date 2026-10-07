package br.com.vistoria.worker.pleno;

import br.com.vistoria.core.modules.integracao.IntegracaoSyncService;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PlenoSyncCron {

    private final IntegracaoSyncService service;

    @Scheduled(fixedDelayString = "${inspecta.pleno.poll-ms:60000}")
    @SchedulerLock(name = "plenoSyncPoller", lockAtMostFor = "5m")
    public void drenar() {
        service.drenarPendentes();
    }
}
