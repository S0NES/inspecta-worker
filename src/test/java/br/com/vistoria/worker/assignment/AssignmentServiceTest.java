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
import br.com.vistoria.core.modules.solicitacao.Solicitacao;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoRepositories.ServicoRepository;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoRepositories.SolicitacaoRepository;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoServico;
import br.com.vistoria.core.shared.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AssignmentService}.
 *
 * <p>Eligibility (ativo + city + distance) and the "day fully blocked" check now live in
 * {@link DisponibilidadeService} (inspecta-core). This class mocks that collaborator and keeps
 * testing what stays here: workload ranking, the 14-day availability slot scan, and the
 * enqueue/backoff path when nobody can take the job.</p>
 *
 * <p>Entities are built as real objects through their setters instead of being mocked: the
 * service mutates the {@link SolicitacaoServico} it receives (status, vistoriadorId,
 * dataAgendada), which a mock cannot record.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AssignmentService")
class AssignmentServiceTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final UUID SERVICO_ID = UUID.randomUUID();
    private static final UUID SOLICITACAO_ID = UUID.randomUUID();
    private static final UUID IMOVEL_ID = UUID.randomUUID();
    private static final UUID CIDADE_ID = UUID.randomUUID();
    private static final UUID TIPO_SERVICO_ID = UUID.randomUUID();
    private static final UUID CLIENTE_ID = UUID.randomUUID();
    private static final UUID V100 = UUID.randomUUID();
    private static final UUID V101 = UUID.randomUUID();
    private static final UUID V999 = UUID.randomUUID();

    @Mock private ServicoRepository servicos;
    @Mock private SolicitacaoRepository solicitacoes;
    @Mock private ImovelRepository imoveis;
    @Mock private VistoriadorRepository vistoriadores;
    @Mock private DisponibilidadeRepository disponibilidades;
    @Mock private DisponibilidadeService disponibilidade;
    @Mock private AtribuicaoRepository atribuicoes;
    @Mock private FilaRepository fila;

    private AssignmentService service;

    @BeforeEach
    void setUp() {
        service = new AssignmentService(servicos, solicitacoes, imoveis, vistoriadores,
            disponibilidades, disponibilidade, atribuicoes, fila);
    }


    @Test
    @DisplayName("processar throws when service not found")
    void processar_serviceNotFound_throwsNotFoundException() {
        when(servicos.findById(SERVICO_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.processar(SERVICO_ID, null, "user", null))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("Servico " + SERVICO_ID);

        verifyNoInteractions(vistoriadores, disponibilidade, atribuicoes, fila);
    }

    @ParameterizedTest
    @EnumSource(value = SolicitacaoServico.Status.class,
                names = {"agendada", "em_execucao", "concluida", "cancelada"})
    @DisplayName("processar returns false when service status is not pendente or atribuida")
    void processar_invalidStatus_returnsFalse(SolicitacaoServico.Status status) {
        when(servicos.findById(SERVICO_ID)).thenReturn(Optional.of(servico(status, 60)));

        AssignmentService.Resultado result = service.processar(SERVICO_ID, null, "user", null);

        assertThat(result.atribuido()).isFalse();
        assertThat(result.vistoriadorId()).isNull();
        assertThat(result.dataAgendada()).isNull();
        verifyNoInteractions(vistoriadores, disponibilidade, atribuicoes, fila);
    }

    @Test
    @DisplayName("processar propagates when the parent solicitacao is missing")
    void processar_solicitacaoMissing_throws() {
        when(servicos.findById(SERVICO_ID))
            .thenReturn(Optional.of(servico(SolicitacaoServico.Status.pendente, 60)));
        when(solicitacoes.findById(SOLICITACAO_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.processar(SERVICO_ID, null, "user", null))
            .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("processar propagates when the imovel is missing")
    void processar_imovelMissing_throws() {
        when(servicos.findById(SERVICO_ID))
            .thenReturn(Optional.of(servico(SolicitacaoServico.Status.pendente, 60)));
        when(solicitacoes.findById(SOLICITACAO_ID)).thenReturn(Optional.of(solicitacao(false)));
        when(imoveis.findById(IMOVEL_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.processar(SERVICO_ID, null, "user", null))
            .isInstanceOf(NoSuchElementException.class);
    }


    @Test
    @DisplayName("processar enqueues when there is no active inspector at all")
    void processar_noEligible_enqueues() {
        cenario(SolicitacaoServico.Status.pendente, 60, false, imovel(10.0, 10.0));
        when(vistoriadores.findByAtivoTrue()).thenReturn(List.of());

        AssignmentService.Resultado result = service.processar(SERVICO_ID, null, "user", null);

        assertThat(result.atribuido()).isFalse();
        FilaAtribuicao enfileirado = capturarFila();
        assertThat(enfileirado.getServicoId()).isEqualTo(SERVICO_ID);
        assertThat(enfileirado.getTentativas()).isEqualTo(1);
        verifyNoInteractions(atribuicoes);
    }

    @Test
    @DisplayName("processar enqueues when DisponibilidadeService rejects every inspector")
    void processar_noneAtendeLocal_enqueues() {
        cenario(SolicitacaoServico.Status.pendente, 60, false, imovel(10.0, 10.0));
        when(vistoriadores.findByAtivoTrue())
            .thenReturn(List.of(vistoriador(V100, new UUID[]{V999}, 10.0, 10.0, 100)));
        when(disponibilidade.atendeLocal(any(), any(), any(), any())).thenReturn(false);

        AssignmentService.Resultado result = service.processar(SERVICO_ID, null, "user", null);

        assertThat(result.atribuido()).isFalse();
        verify(disponibilidades, never()).findByVistoriadorIdAndDiaSemana(any(), anyInt());
        verify(fila).save(any(FilaAtribuicao.class));
    }

    @Test
    @DisplayName("processar excludes the specified inspector before consulting eligibility")
    void processar_withExclusion_filtersOutVistoriador() {
        cenario(SolicitacaoServico.Status.pendente, 60, false, imovel(10.0, 10.0));
        when(vistoriadores.findByAtivoTrue()).thenReturn(List.of(
            vistoriador(V100, new UUID[]{CIDADE_ID}, 10.0, 10.0, 100),
            vistoriador(V101, new UUID[]{CIDADE_ID}, 10.0, 10.0, 100)));
        when(disponibilidade.atendeLocal(any(), any(), any(), any())).thenReturn(true);

        service.processar(SERVICO_ID, V100, "user", null);

        verify(disponibilidade, never()).atendeLocal(argVistoriador(V100), any(), any(), any());
        verify(disponibilidades, never()).findByVistoriadorIdAndDiaSemana(eq(V100), anyInt());
        verify(disponibilidades, atLeastOnce()).findByVistoriadorIdAndDiaSemana(eq(V101), anyInt());
    }

    @Test
    @DisplayName("processar assigns when DisponibilidadeService accepts an inspector with a free window")
    void processar_atendeLocal_assigns() {
        cenario(SolicitacaoServico.Status.pendente, 60, false, imovel(-22.90, -43.21));
        when(vistoriadores.findByAtivoTrue())
            .thenReturn(List.of(vistoriador(V100, new UUID[]{CIDADE_ID}, -23.55, -46.63, 50)));
        when(disponibilidade.atendeLocal(any(), any(), any(), any())).thenReturn(true);
        janelaComercial(V100);

        AssignmentService.Resultado result = service.processar(SERVICO_ID, null, "user", null);

        assertThat(result.atribuido()).isTrue();
        assertThat(result.vistoriadorId()).isEqualTo(V100);
    }


    @Test
    @DisplayName("processar increments retry counter and applies the matching backoff step")
    void processar_retry_incrementsCounterAndSetsBackoff() {
        cenario(SolicitacaoServico.Status.pendente, 60, false, imovel(10.0, 10.0));
        when(vistoriadores.findByAtivoTrue()).thenReturn(List.of());

        FilaAtribuicao existing = new FilaAtribuicao();
        existing.setServicoId(SERVICO_ID);
        existing.setTentativas(2);
        when(fila.findByServicoId(SERVICO_ID)).thenReturn(Optional.of(existing));

        Instant antes = Instant.now();
        service.processar(SERVICO_ID, null, "user", null);

        FilaAtribuicao salvo = capturarFila();
        assertThat(salvo).isSameAs(existing);
        assertThat(salvo.getTentativas()).isEqualTo(3);
        // BACKOFF_MIN[2] == 240 minutes
        assertThat(Duration.between(antes, salvo.getProximaTentativa()).toMinutes())
            .isCloseTo(240L, within(1L));
    }

    @Test
    @DisplayName("processar caps the backoff at the last step for exhausted entries")
    void processar_retryBeyondTable_capsBackoff() {
        cenario(SolicitacaoServico.Status.pendente, 60, false, imovel(10.0, 10.0));
        when(vistoriadores.findByAtivoTrue()).thenReturn(List.of());

        FilaAtribuicao existing = new FilaAtribuicao();
        existing.setServicoId(SERVICO_ID);
        existing.setTentativas(50);
        when(fila.findByServicoId(SERVICO_ID)).thenReturn(Optional.of(existing));

        Instant antes = Instant.now();
        service.processar(SERVICO_ID, null, "user", null);

        FilaAtribuicao salvo = capturarFila();
        assertThat(salvo.getTentativas()).isEqualTo(51);
        // Last BACKOFF_MIN entry == 10080 minutes (7 days); the index must not overflow.
        assertThat(Duration.between(antes, salvo.getProximaTentativa()).toMinutes())
            .isCloseTo(10080L, within(1L));
    }

    @Test
    @DisplayName("processar enqueues when the only inspector has no free window")
    void processar_noWindowAvailable_enqueues() {
        cenarioElegivel(V100);
        // no disponibilidade rows at all -> the 14-day horizon is exhausted

        AssignmentService.Resultado result = service.processar(SERVICO_ID, null, "user", null);

        assertThat(result.atribuido()).isFalse();
        verify(atribuicoes, never()).save(any(Atribuicao.class));
        verify(fila).save(any(FilaAtribuicao.class));
    }

    @Test
    @DisplayName("processar skips days fully blocked by an availability exception")
    void processar_blockedByExcecao_enqueues() {
        cenarioElegivel(V100);
        when(disponibilidade.diaBloqueado(eq(V100), any())).thenReturn(true);

        AssignmentService.Resultado result = service.processar(SERVICO_ID, null, "user", null);

        assertThat(result.atribuido()).isFalse();
        // A blocked day must never be probed for availability windows.
        verify(disponibilidades, never()).findByVistoriadorIdAndDiaSemana(any(), anyInt());
        verify(fila).save(any(FilaAtribuicao.class));
    }

    @Test
    @DisplayName("processar enqueues when every candidate slot collides with the agenda")
    void processar_agendaAlwaysBusy_enqueues() {
        cenarioElegivel(V100);
        janelaComercial(V100);
        when(servicos.agendaOcupada(eq(V100), any(), any()))
            .thenReturn(List.of(servico(SolicitacaoServico.Status.agendada, 60)));

        AssignmentService.Resultado result = service.processar(SERVICO_ID, null, "user", null);

        assertThat(result.atribuido()).isFalse();
        verify(fila).save(any(FilaAtribuicao.class));
    }


    @Test
    @DisplayName("processar assigns the first free slot of the next day")
    void processar_withEligibleAndSlot_assigns() {
        SolicitacaoServico servico = cenarioElegivel(V100);
        janelaComercial(V100);

        AssignmentService.Resultado result = service.processar(SERVICO_ID, null, "user", "test reason");

        assertThat(result.atribuido()).isTrue();
        assertThat(result.vistoriadorId()).isEqualTo(V100);
        assertThat(result.dataAgendada()).isNotNull();

        ZonedDateTime agendado = result.dataAgendada().atZone(SP);
        assertThat(agendado.toLocalTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(agendado.toLocalDate()).isEqualTo(LocalDate.now(SP).plusDays(1));

        assertThat(servico.getStatus()).isEqualTo(SolicitacaoServico.Status.agendada);
        assertThat(servico.getVistoriadorId()).isEqualTo(V100);
        assertThat(servico.getDataAgendada()).isEqualTo(result.dataAgendada());

        Atribuicao atribuicao = capturarAtribuicao();
        assertThat(atribuicao.getServicoId()).isEqualTo(SERVICO_ID);
        assertThat(atribuicao.getVistoriadorId()).isEqualTo(V100);
        assertThat(atribuicao.getAcao()).isEqualTo(Atribuicao.Acao.atribuido);
        assertThat(atribuicao.getMotivo()).isEqualTo("test reason");
        assertThat(atribuicao.getCriadoPor()).isEqualTo("user");
    }

    @Test
    @DisplayName("processar falls back to 60 minutes when duration is not informed")
    void processar_nullDuration_usesSixtyMinuteDefault() {
        cenario(SolicitacaoServico.Status.pendente, null, false, imovel(10.0, 10.0));
        when(vistoriadores.findByAtivoTrue())
            .thenReturn(List.of(vistoriador(V100, new UUID[]{CIDADE_ID}, 10.0, 10.0, 100)));
        when(disponibilidade.atendeLocal(any(), any(), any(), any())).thenReturn(true);
        // Exactly 60 minutes wide: it fits the default duration and nothing longer.
        janela(V100, LocalTime.of(9, 0), LocalTime.of(10, 0));

        AssignmentService.Resultado result = service.processar(SERVICO_ID, null, "user", null);

        assertThat(result.atribuido()).isTrue();
        verify(servicos).agendaOcupada(eq(V100), any(), any());
    }

    @Test
    @DisplayName("processar marks assignment as reatribuido when an exclusion is specified")
    void processar_withExclusion_marksAsReatribuido() {
        cenario(SolicitacaoServico.Status.atribuida, 60, false, imovel(10.0, 10.0));
        when(vistoriadores.findByAtivoTrue())
            .thenReturn(List.of(vistoriador(V100, new UUID[]{CIDADE_ID}, 10.0, 10.0, 100)));
        when(disponibilidade.atendeLocal(any(), any(), any(), any())).thenReturn(true);
        janelaComercial(V100);

        service.processar(SERVICO_ID, V999, "user", "reassignment");

        Atribuicao atribuicao = capturarAtribuicao();
        assertThat(atribuicao.getAcao()).isEqualTo(Atribuicao.Acao.reatribuido);
        assertThat(atribuicao.getMotivo()).isEqualTo("reassignment");
    }

    @Test
    @DisplayName("processar drops the queue entry once the service is finally assigned")
    void processar_successfulAssignment_removesQueueEntry() {
        cenarioElegivel(V100);
        janelaComercial(V100);

        FilaAtribuicao pendente = new FilaAtribuicao();
        pendente.setServicoId(SERVICO_ID);
        pendente.setTentativas(3);
        when(fila.findByServicoId(SERVICO_ID)).thenReturn(Optional.of(pendente));

        service.processar(SERVICO_ID, null, "user", null);

        verify(fila).delete(pendente);
        verify(fila, never()).save(any(FilaAtribuicao.class));
    }

    @Test
    @DisplayName("urgent requests are scheduled from today, normal ones from tomorrow")
    void processar_urgente_startsSearchToday() {
        cenario(SolicitacaoServico.Status.pendente, 60, true, imovel(10.0, 10.0));
        when(vistoriadores.findByAtivoTrue())
            .thenReturn(List.of(vistoriador(V100, new UUID[]{CIDADE_ID}, 10.0, 10.0, 100)));
        when(disponibilidade.atendeLocal(any(), any(), any(), any())).thenReturn(true);

        service.processar(SERVICO_ID, null, "user", null);

        assertThat(primeiroDiaVarrido()).isEqualTo(LocalDate.now(SP));
    }

    @Test
    @DisplayName("normal requests skip today and start the search tomorrow")
    void processar_naoUrgente_startsSearchTomorrow() {
        cenarioElegivel(V100);

        service.processar(SERVICO_ID, null, "user", null);

        assertThat(primeiroDiaVarrido()).isEqualTo(LocalDate.now(SP).plusDays(1));
    }


    @Test
    @DisplayName("processar prefers the inspector with the lighter monthly workload")
    void processar_ranking_prefersLighterWorkload() {
        cenario(SolicitacaoServico.Status.pendente, 60, false, imovel(10.0, 10.0));
        when(vistoriadores.findByAtivoTrue()).thenReturn(List.of(
            vistoriador(V100, new UUID[]{CIDADE_ID}, 10.0, 10.0, 100),
            vistoriador(V101, new UUID[]{CIDADE_ID}, 10.0, 10.0, 100)));
        when(disponibilidade.atendeLocal(any(), any(), any(), any())).thenReturn(true);
        when(atribuicoes.workloadMes(anyList(), any())).thenReturn(List.<Object[]>of(
            new Object[]{V100, 5L, Instant.now()}));
        janelaComercialParaTodos();

        AssignmentService.Resultado result = service.processar(SERVICO_ID, null, "user", null);

        // V101 has no rows this month, so it ranks ahead of V100's five assignments.
        assertThat(result.vistoriadorId()).isEqualTo(V101);
    }

    @Test
    @DisplayName("processar breaks a workload tie by the oldest last assignment")
    void processar_ranking_tieBreaksByOldestAssignment() {
        cenario(SolicitacaoServico.Status.pendente, 60, false, imovel(10.0, 10.0));
        when(vistoriadores.findByAtivoTrue()).thenReturn(List.of(
            vistoriador(V100, new UUID[]{CIDADE_ID}, 10.0, 10.0, 100),
            vistoriador(V101, new UUID[]{CIDADE_ID}, 10.0, 10.0, 100)));
        when(disponibilidade.atendeLocal(any(), any(), any(), any())).thenReturn(true);
        Instant agora = Instant.now();
        when(atribuicoes.workloadMes(anyList(), any())).thenReturn(List.of(
            new Object[]{V100, 3L, agora},
            new Object[]{V101, 3L, agora.minus(Duration.ofDays(5))}));
        janelaComercialParaTodos();

        AssignmentService.Resultado result = service.processar(SERVICO_ID, null, "user", null);

        assertThat(result.vistoriadorId()).isEqualTo(V101);
    }

    @Test
    @DisplayName("processar falls through to the next inspector when the first has no slot")
    void processar_firstInspectorBusy_fallsThroughToSecond() {
        cenario(SolicitacaoServico.Status.pendente, 60, false, imovel(10.0, 10.0));
        when(vistoriadores.findByAtivoTrue()).thenReturn(List.of(
            vistoriador(V100, new UUID[]{CIDADE_ID}, 10.0, 10.0, 100),
            vistoriador(V101, new UUID[]{CIDADE_ID}, 10.0, 10.0, 100)));
        when(disponibilidade.atendeLocal(any(), any(), any(), any())).thenReturn(true);
        // V100 ranks first (both have zero workload, stable order) but publishes no windows.
        when(disponibilidades.findByVistoriadorIdAndDiaSemana(any(), anyInt()))
            .thenAnswer(inv -> V101.equals(inv.getArgument(0))
                ? List.of(janelaEntity(V101, LocalTime.of(9, 0), LocalTime.of(17, 0)))
                : List.of());

        AssignmentService.Resultado result = service.processar(SERVICO_ID, null, "user", null);

        assertThat(result.atribuido()).isTrue();
        assertThat(result.vistoriadorId()).isEqualTo(V101);
    }


    /** Wires the servico -> solicitacao -> imovel chain that every assignment run walks. */
    private SolicitacaoServico cenario(SolicitacaoServico.Status status, Integer duracao,
                                       boolean urgente, Imovel imovel) {
        SolicitacaoServico servico = servico(status, duracao);
        when(servicos.findById(SERVICO_ID)).thenReturn(Optional.of(servico));
        when(solicitacoes.findById(SOLICITACAO_ID)).thenReturn(Optional.of(solicitacao(urgente)));
        when(imoveis.findById(IMOVEL_ID)).thenReturn(Optional.of(imovel));
        return servico;
    }

    /** {@link #cenario} plus one active inspector that {@link DisponibilidadeService} accepts. */
    private SolicitacaoServico cenarioElegivel(UUID vistoriadorId) {
        SolicitacaoServico servico =
            cenario(SolicitacaoServico.Status.pendente, 60, false, imovel(10.0, 10.0));
        when(vistoriadores.findByAtivoTrue())
            .thenReturn(List.of(vistoriador(vistoriadorId, new UUID[]{CIDADE_ID}, 10.0, 10.0, 100)));
        when(disponibilidade.atendeLocal(any(), any(), any(), any())).thenReturn(true);
        return servico;
    }

    private void janelaComercial(UUID vistoriadorId) {
        janela(vistoriadorId, LocalTime.of(9, 0), LocalTime.of(17, 0));
    }

    private void janela(UUID vistoriadorId, LocalTime inicio, LocalTime fim) {
        when(disponibilidades.findByVistoriadorIdAndDiaSemana(eq(vistoriadorId), anyInt()))
            .thenReturn(List.of(janelaEntity(vistoriadorId, inicio, fim)));
    }

    private void janelaComercialParaTodos() {
        when(disponibilidades.findByVistoriadorIdAndDiaSemana(any(), anyInt()))
            .thenAnswer(inv -> List.of(
                janelaEntity(inv.getArgument(0), LocalTime.of(9, 0), LocalTime.of(17, 0))));
    }

    /** First calendar day the availability scan looked at, captured from the blocked-day probe. */
    private LocalDate primeiroDiaVarrido() {
        ArgumentCaptor<LocalDate> captor = ArgumentCaptor.forClass(LocalDate.class);
        verify(disponibilidade, atLeastOnce()).diaBloqueado(eq(V100), captor.capture());
        return captor.getAllValues().get(0);
    }

    private FilaAtribuicao capturarFila() {
        ArgumentCaptor<FilaAtribuicao> captor = ArgumentCaptor.forClass(FilaAtribuicao.class);
        verify(fila).save(captor.capture());
        return captor.getValue();
    }

    private Atribuicao capturarAtribuicao() {
        ArgumentCaptor<Atribuicao> captor = ArgumentCaptor.forClass(Atribuicao.class);
        verify(atribuicoes).save(captor.capture());
        return captor.getValue();
    }

    /** Matches a {@link Vistoriador} argument by id (for verifying eligibility was/was not consulted). */
    private static Vistoriador argVistoriador(UUID id) {
        return org.mockito.ArgumentMatchers.argThat(v -> v != null && id.equals(v.getId()));
    }

    private SolicitacaoServico servico(SolicitacaoServico.Status status, Integer duracao) {
        SolicitacaoServico s = new SolicitacaoServico();
        s.setId(SERVICO_ID);
        s.setSolicitacaoId(SOLICITACAO_ID);
        s.setTipoServicoId(TIPO_SERVICO_ID);
        s.setStatus(status);
        s.setDuracaoEstimadaMin(duracao);
        s.setPrecoSnapshot(BigDecimal.valueOf(250));
        return s;
    }

    private Solicitacao solicitacao(boolean urgente) {
        Solicitacao s = new Solicitacao();
        s.setId(SOLICITACAO_ID);
        s.setClienteId(CLIENTE_ID);
        s.setImovelId(IMOVEL_ID);
        s.setUrgente(urgente);
        s.setOrigem(Solicitacao.Origem.painel);
        return s;
    }

    private Imovel imovel(Double lat, Double lng) {
        Imovel i = new Imovel();
        i.setId(IMOVEL_ID);
        i.setCidadeId(CIDADE_ID);
        i.setEndereco("Rua Teste, 100");
        i.setLat(lat);
        i.setLng(lng);
        i.setMetragem(BigDecimal.valueOf(80));
        return i;
    }

    private Vistoriador vistoriador(UUID id, UUID[] cidades, Double lat, Double lng, int distMax) {
        Vistoriador v = new Vistoriador();
        v.setId(id);
        v.setUsuarioId(id);
        v.setAtivo(true);
        v.setCidades(cidades);
        v.setLatBase(lat);
        v.setLngBase(lng);
        v.setDistanciaMaxKm(BigDecimal.valueOf(distMax));
        return v;
    }

    private Disponibilidade janelaEntity(UUID vistoriadorId, LocalTime inicio, LocalTime fim) {
        Disponibilidade d = new Disponibilidade();
        d.setVistoriadorId(vistoriadorId);
        d.setHoraInicio(inicio);
        d.setHoraFim(fim);
        return d;
    }
}
