package br.com.vistoria.worker.assignment;

import br.com.vistoria.core.modules.atribuicao.AtribuicaoRepositories.FilaRepository;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class FilaAtribuicaoRetryCron {

    private final FilaRepository fila;
    private final AssignmentService assignment;

    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "filaAtribuicaoRetry", lockAtMostFor = "5m")
    @Transactional
    public void retryFila() {
        fila.findByProximaTentativaBefore(Instant.now())
                .forEach(f -> assignment.processar(f.getServicoId(), null, "sistema", null));
    }
}
