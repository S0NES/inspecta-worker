package br.com.vistoria.worker.assignment;

import br.com.vistoria.core.modules.atribuicao.Atribuicao;
import br.com.vistoria.core.modules.atribuicao.AtribuicaoRepositories.AtribuicaoRepository;
import br.com.vistoria.core.modules.atribuicao.AtribuicaoRepositories.FilaRepository;
import br.com.vistoria.core.modules.atribuicao.FilaAtribuicao;
import br.com.vistoria.core.modules.cadastro.CadastroRepositories.ImovelRepository;
import br.com.vistoria.core.modules.cadastro.Imovel;
import br.com.vistoria.core.modules.disponibilidade.Disponibilidade;
import br.com.vistoria.core.modules.disponibilidade.DisponibilidadeRepositories.DisponibilidadeRepository;
import br.com.vistoria.core.modules.disponibilidade.DisponibilidadeRepositories.VistoriadorRepository;
import br.com.vistoria.core.modules.disponibilidade.DisponibilidadeService;
import br.com.vistoria.core.modules.disponibilidade.Vistoriador;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoRepositories.ServicoRepository;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoRepositories.SolicitacaoRepository;
import br.com.vistoria.core.modules.solicitacao.Solicitacao;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoServico;
import br.com.vistoria.core.shared.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * Atribuicao automatica de vistoriador a um servico.
 *
 * <p>A elegibilidade (ativo + cidade + distancia) e a checagem de dia bloqueado ficam no
 * {@link DisponibilidadeService} (inspecta-core), que o {@code inspecta-api} tambem usa para
 * bloquear o pagamento. Aqui ficam apenas o ranking por carga mensal, a varredura de
 * janelas de agenda e o enfileiramento com backoff.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AssignmentService {

    private static final int[] BACKOFF_MIN = {15, 60, 240, 720, 1440, 2880, 10080};

    private final ServicoRepository servicos;
    private final SolicitacaoRepository solicitacoes;
    private final ImovelRepository imoveis;
    private final VistoriadorRepository vistoriadores;
    private final DisponibilidadeRepository disponibilidades;
    private final DisponibilidadeService disponibilidade;
    private final AtribuicaoRepository atribuicoes;
    private final FilaRepository fila;

    public record Resultado(boolean atribuido, UUID vistoriadorId, Instant dataAgendada) {}

    @Transactional
    public Resultado processar(UUID servicoId, UUID excluirVistoriador, String criadoPor, String motivo) {
        SolicitacaoServico servico = servicos.findById(servicoId)
                .orElseThrow(() -> new NotFoundException("Servico " + servicoId));
        if (servico.getStatus() != SolicitacaoServico.Status.pendente
                && servico.getStatus() != SolicitacaoServico.Status.atribuida) {
            return new Resultado(false, null, null);
        }

        Solicitacao sol = solicitacoes.findById(servico.getSolicitacaoId()).orElseThrow();
        Imovel imovel = imoveis.findById(sol.getImovelId()).orElseThrow();
        int duracaoMin = servico.getDuracaoEstimadaMin() != null ? servico.getDuracaoEstimadaMin() : 60;

        List<Vistoriador> elegiveis = vistoriadores.findByAtivoTrue().stream()
                .filter(v -> excluirVistoriador == null || !v.getId().equals(excluirVistoriador))
                .filter(v -> disponibilidade.atendeLocal(
                        v, imovel.getCidadeId(), imovel.getLat(), imovel.getLng()))
                .toList();

        if (elegiveis.isEmpty()) return enfileirar(servicoId);

        Instant inicioMes = LocalDate.now().with(TemporalAdjusters.firstDayOfMonth())
                .atStartOfDay(ZoneId.of("America/Sao_Paulo")).toInstant();
        Map<UUID, long[]> workload = new HashMap<>();
        for (Object[] row : atribuicoes.workloadMes(
                elegiveis.stream().map(Vistoriador::getId).toList(), inicioMes)) {
            workload.put((UUID) row[0], new long[]{(Long) row[1], ((Instant) row[2]).getEpochSecond()});
        }

        List<Vistoriador> ranqueados = elegiveis.stream()
                .sorted(Comparator
                        .comparingLong((Vistoriador v) -> workload.getOrDefault(v.getId(), new long[]{0, 0})[0])
                        .thenComparingLong(v -> workload.getOrDefault(v.getId(), new long[]{0, 0})[1]))
                .toList();

        for (Vistoriador v : ranqueados) {
            Optional<Instant> slot = primeiraJanela(v, duracaoMin, sol.isUrgente());
            if (slot.isPresent()) {
                atribuir(servico, v.getId(), slot.get(), excluirVistoriador != null, criadoPor, motivo);
                fila.findByServicoId(servicoId).ifPresent(fila::delete);
                return new Resultado(true, v.getId(), slot.get());
            }
        }
        return enfileirar(servicoId);
    }

    private void atribuir(SolicitacaoServico servico, UUID vistoriadorId, Instant quando,
                          boolean reatribuicao, String criadoPor, String motivo) {
        servico.setVistoriadorId(vistoriadorId);
        servico.setDataAgendada(quando);
        servico.setStatus(SolicitacaoServico.Status.agendada);

        Atribuicao a = new Atribuicao();
        a.setServicoId(servico.getId());
        a.setVistoriadorId(vistoriadorId);
        a.setAcao(reatribuicao ? Atribuicao.Acao.reatribuido : Atribuicao.Acao.atribuido);
        a.setMotivo(motivo);
        a.setCriadoPor(criadoPor);
        atribuicoes.save(a);
        log.info("Servico {} -> vistoriador {} em {}", servico.getId(), vistoriadorId, quando);
    }

    private Resultado enfileirar(UUID servicoId) {
        FilaAtribuicao f = fila.findByServicoId(servicoId).orElseGet(() -> {
            FilaAtribuicao n = new FilaAtribuicao();
            n.setServicoId(servicoId);
            return n;
        });
        int idx = Math.min(f.getTentativas(), BACKOFF_MIN.length - 1);
        f.setTentativas(f.getTentativas() + 1);
        f.setProximaTentativa(Instant.now().plus(Duration.ofMinutes(BACKOFF_MIN[idx])));
        fila.save(f);
        log.warn("Servico {} sem vistoriador elegivel. Tentativa {}. Proxima {}",
                servicoId, f.getTentativas(), f.getProximaTentativa());
        return new Resultado(false, null, null);
    }

    private Optional<Instant> primeiraJanela(Vistoriador v, int duracaoMin, boolean urgente) {
        ZoneId zone = ZoneId.of("America/Sao_Paulo");
        LocalDate dia = LocalDate.now(zone).plusDays(urgente ? 0 : 1);
        int horizonte = 14;

        for (int i = 0; i < horizonte; i++, dia = dia.plusDays(1)) {
            LocalDate d = dia;
            if (disponibilidade.diaBloqueado(v.getId(), d)) continue;

            List<Disponibilidade> janelas =
                    disponibilidades.findByVistoriadorIdAndDiaSemana(v.getId(), d.getDayOfWeek().getValue() % 7);
            for (Disponibilidade j : janelas) {
                LocalTime t = j.getHoraInicio();
                while (!t.plusMinutes(duracaoMin).isAfter(j.getHoraFim())) {
                    Instant inicio = d.atTime(t).atZone(zone).toInstant();
                    Instant fim = inicio.plus(Duration.ofMinutes(duracaoMin));
                    if (inicio.isAfter(Instant.now())
                            && servicos.agendaOcupada(v.getId(), inicio, fim).isEmpty()) {
                        return Optional.of(inicio);
                    }
                    t = t.plusMinutes(30);
                }
            }
        }
        return Optional.empty();
    }
}
