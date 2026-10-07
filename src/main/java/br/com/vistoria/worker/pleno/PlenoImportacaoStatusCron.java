package br.com.vistoria.worker.pleno;

import br.com.vistoria.core.modules.integracao.IntegracaoSync;
import br.com.vistoria.core.modules.integracao.IntegracaoSyncService;
import br.com.vistoria.core.modules.integracao.PlenoImportacaoService;
import br.com.vistoria.core.modules.integracao.PlenoImportacaoVistoria;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoRepositories.ServicoRepository;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoServico;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Poller de status da vistoria na Pleno — "a volta" do ciclo de espelhamento
 * ({@code PLENO_INTEGRATION_PROMPT.md} § 5). Mesmo idioma de {@code PlenoSyncCron}
 * ({@code @Scheduled} + {@code @SchedulerLock}); estado 100% em Postgres (regra 7).
 *
 * <p>Fluxo por ciclo:</p>
 * <ol>
 *   <li>le o checkpoint incremental ({@link PlenoImportacaoService#lerCheckpoint()});</li>
 *   <li>indexa localmente as solicitacoes ja espelhadas ({@code integracao_sync}
 *       {@code recurso_tipo='solicitacao'} + {@code external_id});</li>
 *   <li>pagina {@code GET /api/vistoria/solicitacao} numa janela de 60 dias (o filtro da Pleno
 *       pode ser por criacao — janela generosa nao perde atualizacao de item antigo);</li>
 *   <li>casa {@code item.sol_codigo} com o indice — sem match, ignora (nao e nosso);</li>
 *   <li>classifica {@code status_solicitacao.sta_sol_codigo} ({@link StatusVistoriaPleno}) e,
 *       para cada {@code solicitacao_servico}, grava progresso / dispara ingestao / marca erro.</li>
 * </ol>
 *
 * <p>Kill switch operacional: {@code inspecta.pleno.importacao.enabled=false} (mesma convencao de
 * {@code inspecta.pleno.mode}). O client de LEITURA e escolhido por {@code inspecta.pleno.mode}
 * ({@code mock}/{@code http}) no mesmo padrao {@code @Qualifier} de {@code PlenoSyncConsumer}.</p>
 */
@Component
@Slf4j
public class PlenoImportacaoStatusCron {

    private static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");
    private static final int JANELA_DIAS = 60;
    private static final int PAGINA_INDICE = 200;
    private static final int MAX_PAGINAS_INDICE = 100;
    private static final int MAX_PAGINAS_LISTAGEM = 200;
    /** Verificacoes seguidas sem progredir de {@code FINALIZADA_SEM_PDF}/erro antes de escalar para {@code ERRO}. */
    private static final int MAX_TENTATIVAS = 8;

    private static final Set<String> IMPORTACAO_TERMINAL = Set.of(
            "PDF_BAIXADO", "ENVIADA_PARA_ASSINATURA", "CANCELADA", "ERRO");

    private final PlenoImportacaoService importacao;
    private final IntegracaoSyncService integracaoSync;
    private final ServicoRepository servicos;
    private final PlenoLaudoIngestionService ingestion;
    private final PlenoReadClient readMock;
    private final PlenoReadClient readHttp;

    @Value("${inspecta.pleno.importacao.enabled:true}") private boolean enabled;
    @Value("${inspecta.pleno.mode:mock}") private String modo;

    public PlenoImportacaoStatusCron(PlenoImportacaoService importacao,
                                     IntegracaoSyncService integracaoSync,
                                     ServicoRepository servicos,
                                     PlenoLaudoIngestionService ingestion,
                                     @Qualifier("plenoReadMock") PlenoReadClient readMock,
                                     @Qualifier("plenoReadHttp") PlenoReadClient readHttp) {
        this.importacao = importacao;
        this.integracaoSync = integracaoSync;
        this.servicos = servicos;
        this.ingestion = ingestion;
        this.readMock = readMock;
        this.readHttp = readHttp;
    }

    private PlenoReadClient client() {
        if ("http".equals(modo)) return readHttp;
        if ("mock".equals(modo)) return readMock;
        throw new IllegalStateException("inspecta.pleno.mode invalido: '" + modo + "'. Valores: http, mock");
    }

    @Scheduled(fixedDelayString = "${inspecta.pleno.importacao.poll-ms:900000}")
    @SchedulerLock(name = "plenoImportacaoStatusPoller")
    public void executar() {
        if (!enabled) {
            log.debug("[pleno-importacao] desabilitado (inspecta.pleno.importacao.enabled=false)");
            return;
        }

        Instant agora = Instant.now();
        LocalDate hoje = LocalDate.now(ZONA);
        LocalDate janela = hoje.minusDays(JANELA_DIAS);
        LocalDate desde = importacao.lerCheckpoint()
                .map(cp -> LocalDate.ofInstant(cp, ZONA))
                .filter(janela::isAfter)   // so recua alem de 60d se o checkpoint for ainda mais antigo (downtime longo)
                .orElse(janela);

        Map<String, UUID> indice = indexarSolicitacoesEspelhadas();
        if (indice.isEmpty()) {
            log.debug("[pleno-importacao] nenhuma solicitacao espelhada — nada a acompanhar");
            importacao.salvarCheckpoint(agora);
            return;
        }

        PlenoReadClient client = client();
        int pagina = 1;
        int atualizados = 0;
        try {
            while (pagina <= MAX_PAGINAS_LISTAGEM) {
                List<PlenoSolicitacaoItem> itens = client.listarSolicitacoes(desde, hoje, pagina);
                if (itens.isEmpty()) break;
                for (PlenoSolicitacaoItem item : itens) {
                    atualizados += processarItem(item, indice, client);
                }
                pagina++;
            }
        } catch (RuntimeException e) {
            // Mesmo canal de alerta do DlqMonitor (slf4j). Nao salva checkpoint — retoma do mesmo ponto.
            log.error("[pleno-importacao] ALERTA: falha ao paginar GET /api/vistoria/solicitacao (pagina {}): {}",
                    pagina, e.getMessage(), e);
            return;
        }

        importacao.salvarCheckpoint(agora);
        log.info("[pleno-importacao] ciclo ok: {} pagina(s), {} servico(s) atualizados", pagina - 1, atualizados);
    }

    /** {@code integracao_sync.external_id} (= {@code sol_codigo} Pleno) -> {@code solicitacoes.id}. */
    private Map<String, UUID> indexarSolicitacoesEspelhadas() {
        Map<String, UUID> indice = new HashMap<>();
        for (int p = 0; p < MAX_PAGINAS_INDICE; p++) {
            Page<IntegracaoSync> page = integracaoSync.listar("sincronizado", PageRequest.of(p, PAGINA_INDICE));
            for (IntegracaoSync sync : page.getContent()) {
                if (!"solicitacao".equals(sync.getRecursoTipo())) continue;
                String ext = sync.getExternalId();
                if (ext != null && !ext.isBlank()) {
                    indice.put(ext.trim(), sync.getRecursoId());
                }
            }
            if (page.isLast() || page.getContent().isEmpty()) break;
        }
        return indice;
    }

    /** @return quantos {@code solicitacao_servico} foram efetivamente atualizados */
    private int processarItem(PlenoSolicitacaoItem item, Map<String, UUID> indice, PlenoReadClient client) {
        UUID solicitacaoId = indice.get(item.solCodigo());
        if (solicitacaoId == null) {
            return 0; // nao e nosso, ou ainda nao espelhado
        }
        StatusVistoriaPleno status = StatusVistoriaPleno.fromCodigo(item.staSolCodigo());

        int n = 0;
        for (SolicitacaoServico ss : servicos.findBySolicitacaoId(solicitacaoId)) {
            try {
                n += processarServico(ss, item, status, client);
            } catch (RuntimeException e) {
                registrarFalha(ss.getId(), item, e);
            }
        }
        return n;
    }

    private int processarServico(SolicitacaoServico ss, PlenoSolicitacaoItem item,
                                 StatusVistoriaPleno status, PlenoReadClient client) {
        UUID servicoId = ss.getId();
        PlenoImportacaoVistoria row = importacao.buscar(servicoId).orElse(null);

        if (row != null
                && (row.getEnviadoParaAssinaturaEm() != null
                || IMPORTACAO_TERMINAL.contains(row.getStatusImportacao()))) {
            return 0; // ja concluido — nao regride
        }

        switch (status) {
            case CANCELADA -> {
                importacao.registrarProgresso(servicoId, item.staSolCodigo(),
                        PlenoImportacaoVistoria.Status.CANCELADA, null);
                log.info("[pleno-importacao] servico {} -> CANCELADA (sol_codigo {})", servicoId, item.solCodigo());
                return 1;
            }
            case CONCLUIDA -> {
                String link = item.temLinkPdf() ? item.linkPdf() : null;
                if (link == null && item.visCodigo() != null) {
                    link = client.linkPdfDaVistoria(item.visCodigo()).orElse(null);
                }
                if (row == null) {
                    importacao.registrarProgresso(servicoId, item.staSolCodigo(),
                            PlenoImportacaoVistoria.Status.EM_ANDAMENTO, null);
                }
                if (link != null) {
                    importacao.marcarPdfDisponivel(servicoId, link);
                    ingestion.ingerir(servicoId, link);
                } else {
                    PlenoImportacaoVistoria after = importacao.registrarProgresso(servicoId, item.staSolCodigo(),
                            PlenoImportacaoVistoria.Status.FINALIZADA_SEM_PDF,
                            Instant.now().plus(Duration.ofHours(4)));
                    escalarSeExcedido(after, "CONCLUIDA na Pleno sem link_pdf apos "
                            + after.getTentativasVerificacao() + " verificacoes");
                }
                return 1;
            }
            default -> {
                if (row != null && row.getProximaVerificacaoEm() != null
                        && row.getProximaVerificacaoEm().isAfter(Instant.now())) {
                    return 0; // backoff ainda nao venceu
                }
                if (status == StatusVistoriaPleno.DESCONHECIDO) {
                    log.warn("[pleno-importacao] ALERTA: sta_sol_codigo desconhecido ({}) sol_codigo={} "
                            + "— tratando como EM_ANDAMENTO", item.staSolCodigo(), item.solCodigo());
                }
                importacao.registrarProgresso(servicoId, item.staSolCodigo(),
                        status.statusImportacaoEmAndamento(),
                        Instant.now().plus(status.proximaVerificacao()));
                return 1;
            }
        }
    }

    private void registrarFalha(UUID servicoId, PlenoSolicitacaoItem item, RuntimeException e) {
        log.warn("[pleno-importacao] falha ao processar servico {} (sol_codigo {}): {}",
                servicoId, item.solCodigo(), e.getMessage());
        if (importacao.buscar(servicoId).isEmpty()) return;
        PlenoImportacaoVistoria row = importacao.incrementarTentativa(
                servicoId, Instant.now().plus(Duration.ofMinutes(15)));
        escalarSeExcedido(row, "falhas repetidas ao processar: " + e.getMessage());
    }

    /** Sem retry infinito silencioso: apos {@link #MAX_TENTATIVAS} verificacoes -> ERRO + alerta. */
    private void escalarSeExcedido(PlenoImportacaoVistoria row, String motivo) {
        if (row.getTentativasVerificacao() >= MAX_TENTATIVAS
                && !"ERRO".equals(row.getStatusImportacao())) {
            importacao.marcarErro(row.getSolicitacaoServicoId(), motivo);
            log.error("[pleno-importacao] ALERTA: importacao {} escalada para ERRO — {}", row.getId(), motivo);
        }
    }
}
