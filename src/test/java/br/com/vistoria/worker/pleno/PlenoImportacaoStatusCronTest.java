package br.com.vistoria.worker.pleno;

import br.com.vistoria.core.modules.integracao.IntegracaoSync;
import br.com.vistoria.core.modules.integracao.IntegracaoSyncService;
import br.com.vistoria.core.modules.integracao.PlenoImportacaoService;
import br.com.vistoria.core.modules.integracao.PlenoImportacaoVistoria;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoRepositories.ServicoRepository;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoServico;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PlenoImportacaoStatusCron — poller de status/PDF da Pleno")
class PlenoImportacaoStatusCronTest {

    @Mock private PlenoImportacaoService importacao;
    @Mock private IntegracaoSyncService integracaoSync;
    @Mock private ServicoRepository servicos;
    @Mock private PlenoLaudoIngestionService ingestion;
    @Mock private PlenoReadClient readMock;
    @Mock private PlenoReadClient readHttp;

    private final ObjectMapper mapper = new ObjectMapper();
    private PlenoImportacaoStatusCron cron;

    private final UUID solicitacaoId = UUID.randomUUID();
    private final UUID servicoId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        cron = new PlenoImportacaoStatusCron(importacao, integracaoSync, servicos, ingestion, readMock, readHttp);
        ReflectionTestUtils.setField(cron, "enabled", true);
        ReflectionTestUtils.setField(cron, "modo", "http");

        when(importacao.lerCheckpoint()).thenReturn(Optional.empty());
        // indice: sol_codigo "S1" -> solicitacaoId
        IntegracaoSync sync = new IntegracaoSync();
        sync.setRecursoTipo("solicitacao");
        sync.setRecursoId(solicitacaoId);
        sync.setExternalId("S1");
        when(integracaoSync.listar(eq("sincronizado"), any()))
                .thenReturn(new PageImpl<>(List.of(sync)));

        SolicitacaoServico ss = new SolicitacaoServico();
        ss.setId(servicoId);
        ss.setSolicitacaoId(solicitacaoId);
        when(servicos.findBySolicitacaoId(solicitacaoId)).thenReturn(List.of(ss));
        when(importacao.buscar(servicoId)).thenReturn(Optional.empty());
        when(importacao.registrarProgresso(any(), any(), any(), any())).thenAnswer(inv -> {
            PlenoImportacaoVistoria r = new PlenoImportacaoVistoria();
            r.setId(UUID.randomUUID());
            r.setSolicitacaoServicoId(servicoId);
            return r;
        });
    }

    private JsonNode json(String s) {
        try { return mapper.readTree(s); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private void listagemHttp(List<PlenoSolicitacaoItem> pagina1) {
        when(readHttp.listarSolicitacoes(any(), any(), eq(1))).thenReturn(pagina1);
        when(readHttp.listarSolicitacoes(any(), any(), eq(2))).thenReturn(List.of());
    }

    @Test
    @DisplayName("enabled=false: nao toca nada")
    void desabilitado() {
        ReflectionTestUtils.setField(cron, "enabled", false);
        cron.executar();
        verify(integracaoSync, never()).listar(any(), any());
        verify(importacao, never()).salvarCheckpoint(any());
    }

    @Test
    @DisplayName("CONCLUIDA com link_pdf (item.vistoria): grava link e dispara ingestao; checkpoint salvo")
    void concluidaComPdf_disparaIngestao() {
        var item = new PlenoSolicitacaoItem("S1", 4, "V9", "https://pleno/real/laudo.pdf");
        listagemHttp(List.of(item));

        cron.executar();

        verify(importacao).marcarPdfDisponivel(eq(servicoId), eq("https://pleno/real/laudo.pdf"));
        verify(ingestion).ingerir(servicoId, "https://pleno/real/laudo.pdf");
        verify(importacao).salvarCheckpoint(any());
    }

    @Test
    @DisplayName("listagem so com vistoria_modelo: NUNCA usa o link do modelo; vai p/ FINALIZADA_SEM_PDF")
    void listagem_vistoriaModeloNaoVaza() {
        // o mock devolve itens parseados pelo parser real -> exercita vistoria vs vistoria_modelo
        PlenoSolicitacaoItem soModelo = PlenoSolicitacaoItem.from(json("""
            { "sol_codigo": "S1", "status_solicitacao": { "sta_sol_codigo": 4 },
              "vistoria_modelo": { "vis_codigo": "MOD", "link_pdf": "https://pleno/modelo-NAO-USAR.pdf" } }
            """));
        listagemHttp(List.of(soModelo));

        cron.executar();

        verify(ingestion, never()).ingerir(any(), any());
        verify(readHttp, never()).linkPdfDaVistoria(any());
        verify(importacao).registrarProgresso(eq(servicoId), eq(4),
                eq(PlenoImportacaoVistoria.Status.FINALIZADA_SEM_PDF), any());
    }

    @Test
    @DisplayName("CONCLUIDA sem link mas com vis_codigo: tenta /vistoria/{id}/show; achou -> ingestao")
    void concluidaSemLink_fallbackShow() {
        var item = new PlenoSolicitacaoItem("S1", 4, "V9", null);
        listagemHttp(List.of(item));
        when(readHttp.linkPdfDaVistoria("V9")).thenReturn(Optional.of("https://pleno/show/laudo.pdf"));

        cron.executar();

        verify(readHttp).linkPdfDaVistoria("V9");
        verify(ingestion).ingerir(servicoId, "https://pleno/show/laudo.pdf");
    }

    @Test
    @DisplayName("CANCELADA: registra progresso CANCELADA (terminal, sem proxima verificacao)")
    void cancelada() {
        var item = new PlenoSolicitacaoItem("S1", 5, null, null);
        listagemHttp(List.of(item));

        cron.executar();

        verify(importacao).registrarProgresso(eq(servicoId), eq(5),
                eq(PlenoImportacaoVistoria.Status.CANCELADA), isNull());
        verify(ingestion, never()).ingerir(any(), any());
    }

    @Test
    @DisplayName("EM_ANDAMENTO: registra progresso + backoff de 2h")
    void emAndamento() {
        var item = new PlenoSolicitacaoItem("S1", 3, null, null);
        listagemHttp(List.of(item));

        cron.executar();

        verify(importacao).registrarProgresso(eq(servicoId), eq(3),
                eq(PlenoImportacaoVistoria.Status.EM_ANDAMENTO), any());
    }

    @Test
    @DisplayName("sta_sol_codigo desconhecido: nao lanca, trata como EM_ANDAMENTO")
    void desconhecido() {
        var item = new PlenoSolicitacaoItem("S1", 42, null, null);
        listagemHttp(List.of(item));

        cron.executar();

        verify(importacao).registrarProgresso(eq(servicoId), eq(42),
                eq(PlenoImportacaoVistoria.Status.EM_ANDAMENTO), any());
        verify(importacao).salvarCheckpoint(any());
    }

    @Test
    @DisplayName("sol_codigo sem match no indice local: ignorado")
    void semMatch_ignora() {
        var item = new PlenoSolicitacaoItem("OUTRO-999", 4, "V1", "https://x/y.pdf");
        listagemHttp(List.of(item));

        cron.executar();

        verify(servicos, never()).findBySolicitacaoId(any());
        verify(ingestion, never()).ingerir(any(), any());
        verify(importacao).salvarCheckpoint(any());
    }

    @Test
    @DisplayName("falha ao paginar a listagem: alerta e NAO salva checkpoint")
    void falhaListagem_naoSalvaCheckpoint() {
        when(readHttp.listarSolicitacoes(any(), any(), eq(1)))
                .thenThrow(new IllegalStateException("Pleno 503"));

        cron.executar();

        verify(importacao, never()).salvarCheckpoint(any());
    }

    @Test
    @DisplayName("linha ja ENVIADA_PARA_ASSINATURA: nao regride")
    void jaEnviada_naoRegride() {
        PlenoImportacaoVistoria row = new PlenoImportacaoVistoria();
        row.setId(UUID.randomUUID());
        row.setSolicitacaoServicoId(servicoId);
        row.setStatusImportacao("ENVIADA_PARA_ASSINATURA");
        when(importacao.buscar(servicoId)).thenReturn(Optional.of(row));

        var item = new PlenoSolicitacaoItem("S1", 4, "V9", "https://pleno/real/laudo.pdf");
        listagemHttp(List.of(item));

        cron.executar();

        verify(ingestion, never()).ingerir(any(), any());
        verify(importacao, never()).registrarProgresso(any(), any(), any(), any());
    }

    @Test
    @DisplayName("8 polls EM_ANDAMENTO depois CONCLUIDA sem link: NAO escala (contador zera na mudanca de status)")
    void emAndamentoNaoVazaParaSemPdf() {
        PlenoImportacaoVistoria row = new PlenoImportacaoVistoria();
        row.setId(UUID.randomUUID());
        row.setSolicitacaoServicoId(servicoId);
        row.setStatusImportacao("EM_ANDAMENTO");
        row.setTentativasVerificacao(8);
        when(importacao.buscar(servicoId)).thenReturn(Optional.of(row));
        when(importacao.registrarProgresso(eq(servicoId), eq(4),
                eq(PlenoImportacaoVistoria.Status.FINALIZADA_SEM_PDF), any())).thenAnswer(inv -> {
            PlenoImportacaoVistoria after = new PlenoImportacaoVistoria();
            after.setId(row.getId());
            after.setSolicitacaoServicoId(servicoId);
            after.setStatusImportacao("FINALIZADA_SEM_PDF");
            after.setTentativasVerificacao(0);
            return after;
        });

        var item = new PlenoSolicitacaoItem("S1", 4, null, null);
        listagemHttp(List.of(item));

        cron.executar();

        verify(importacao, never()).marcarErro(any(), any());
        verify(ingestion, never()).ingerir(any(), any());
    }

    @Test
    @DisplayName("8 verificacoes em FINALIZADA_SEM_PDF: escala para ERRO")
    void finalizadaSemPdf_oitava_escala() {
        PlenoImportacaoVistoria row = new PlenoImportacaoVistoria();
        row.setId(UUID.randomUUID());
        row.setSolicitacaoServicoId(servicoId);
        row.setStatusImportacao("FINALIZADA_SEM_PDF");
        row.setTentativasVerificacao(7);
        when(importacao.buscar(servicoId)).thenReturn(Optional.of(row));
        when(importacao.registrarProgresso(eq(servicoId), eq(4),
                eq(PlenoImportacaoVistoria.Status.FINALIZADA_SEM_PDF), any())).thenAnswer(inv -> {
            PlenoImportacaoVistoria after = new PlenoImportacaoVistoria();
            after.setId(row.getId());
            after.setSolicitacaoServicoId(servicoId);
            after.setStatusImportacao("FINALIZADA_SEM_PDF");
            after.setTentativasVerificacao(8);
            return after;
        });

        var item = new PlenoSolicitacaoItem("S1", 4, null, null);
        listagemHttp(List.of(item));

        cron.executar();

        verify(importacao).marcarErro(eq(servicoId), org.mockito.ArgumentMatchers.contains("sem link_pdf"));
    }

    @Test
    @DisplayName("falha de ingestao incrementa tentativas; na 8a marca ERRO")
    void ingestaoFalha_oitava_escala() {
        PlenoImportacaoVistoria row = new PlenoImportacaoVistoria();
        row.setId(UUID.randomUUID());
        row.setSolicitacaoServicoId(servicoId);
        row.setStatusImportacao("FINALIZADA_COM_PDF");
        row.setTentativasVerificacao(7);
        when(importacao.buscar(servicoId)).thenReturn(Optional.of(row));
        when(importacao.incrementarTentativa(eq(servicoId), any())).thenAnswer(inv -> {
            row.setTentativasVerificacao(row.getTentativasVerificacao() + 1);
            return row;
        });
        org.mockito.Mockito.doThrow(new IllegalStateException("link quebrado"))
                .when(ingestion).ingerir(eq(servicoId), any());

        var item = new PlenoSolicitacaoItem("S1", 4, "V9", "https://pleno/real/laudo.pdf");
        listagemHttp(List.of(item));

        cron.executar();

        verify(importacao).incrementarTentativa(eq(servicoId), any());
        verify(importacao).marcarErro(eq(servicoId), org.mockito.ArgumentMatchers.contains("link quebrado"));
    }

    @Test
    @DisplayName("@Scheduled/@SchedulerLock configurados como PlenoSyncCron")
    void anotacoes() throws NoSuchMethodException {
        Method m = PlenoImportacaoStatusCron.class.getDeclaredMethod("executar");
        Scheduled scheduled = m.getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString()).isEqualTo("${inspecta.pleno.importacao.poll-ms:900000}");
        SchedulerLock lock = m.getAnnotation(SchedulerLock.class);
        assertThat(lock).isNotNull();
        assertThat(lock.name()).isEqualTo("plenoImportacaoStatusPoller");
    }

    @Test
    @DisplayName("modo invalido: lanca ao escolher o client de leitura")
    void modoInvalido() {
        ReflectionTestUtils.setField(cron, "modo", "banana");
        var item = new PlenoSolicitacaoItem("S1", 3, null, null);
        // a listagem nunca e chamada — client() lanca antes
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> cron.executar())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inspecta.pleno.mode");
    }
}
