package br.com.vistoria.worker.pleno;

import br.com.vistoria.core.events.EventTypes;
import br.com.vistoria.core.gateway.HttpGateway;
import br.com.vistoria.core.gateway.HttpGatewayRequest;
import br.com.vistoria.core.gateway.HttpGatewayResponse;
import br.com.vistoria.core.modules.arquivo.Arquivo;
import br.com.vistoria.core.modules.arquivo.ArquivoService;
import br.com.vistoria.core.modules.integracao.PlenoImportacaoService;
import br.com.vistoria.core.modules.integracao.PlenoImportacaoVistoria;
import br.com.vistoria.core.modules.laudo.Laudo;
import br.com.vistoria.core.modules.laudo.LaudoRepository;
import br.com.vistoria.core.modules.laudo.LaudoService;
import br.com.vistoria.core.shared.outbox.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PlenoLaudoIngestionService — download -> arquivo -> laudo -> assinatura (idempotente)")
class PlenoLaudoIngestionServiceTest {

    @Mock private HttpGateway httpGateway;
    @Mock private ArquivoService arquivoService;
    @Mock private LaudoService laudoService;
    @Mock private LaudoRepository laudoRepository;
    @Mock private PlenoImportacaoService importacao;
    @Mock private OutboxService outbox;

    private PlenoLaudoIngestionService service;

    private final UUID servicoId = UUID.randomUUID();
    private final UUID rowId = UUID.randomUUID();
    private final UUID laudoId = UUID.randomUUID();
    private final UUID arquivoId = UUID.randomUUID();
    private final String link = "https://pleno/laudo-555.pdf";
    private static final byte[] PDF = "%PDF-1.7 conteudo".getBytes(StandardCharsets.ISO_8859_1);

    private PlenoImportacaoVistoria row;

    @BeforeEach
    void setUp() throws Exception {
        service = new PlenoLaudoIngestionService(
                httpGateway, arquivoService, laudoService, laudoRepository, importacao, outbox);

        row = new PlenoImportacaoVistoria();
        row.setId(rowId);
        row.setSolicitacaoServicoId(servicoId);
        row.setStatusImportacao("FINALIZADA_COM_PDF");

        when(importacao.buscar(servicoId)).thenReturn(Optional.of(row));

        // download OK
        when(httpGateway.execute(any(HttpGatewayRequest.class)))
                .thenReturn(new HttpGatewayResponse(200, new String(PDF, StandardCharsets.ISO_8859_1)));

        // laudo ainda nao existe -> cria
        when(laudoRepository.findFirstBySolicitacaoServicoIdOrderByVersaoDesc(servicoId))
                .thenReturn(Optional.empty());
        Laudo laudo = new Laudo();
        laudo.setId(laudoId);
        laudo.setVersao(1);
        when(laudoService.criar(servicoId)).thenReturn(laudo);

        Arquivo arquivo = new Arquivo();
        arquivo.setId(arquivoId);
        when(arquivoService.registrar(anyString(), eq("application/pdf"), anyLong(), eq("laudo"), eq(laudoId), eq(true)))
                .thenReturn(arquivo);

        // mocks de estado: marcar* mutam a linha compartilhada (simula o flush do JPA)
        when(importacao.marcarPdfBaixado(eq(servicoId), eq(arquivoId), anyString(), eq(laudoId))).thenAnswer(inv -> {
            row.setPdfArquivoId(arquivoId);
            row.setPdfSha256(inv.getArgument(2));
            row.setLaudoId(laudoId);
            row.setStatusImportacao("PDF_BAIXADO");
            return row;
        });
        when(importacao.marcarEnviadoParaAssinatura(servicoId)).thenAnswer(inv -> {
            row.setEnviadoParaAssinaturaEm(Instant.now());
            row.setStatusImportacao("ENVIADA_PARA_ASSINATURA");
            return row;
        });
    }

    @Test
    @DisplayName("primeira ingestao: baixa, grava arquivo, finaliza laudo importado e publica jobs.signature.send")
    void primeiraIngestao() {
        service.ingerir(servicoId, link);

        verify(laudoService).criar(servicoId);
        verify(arquivoService).registrar(anyString(), eq("application/pdf"), eq((long) PDF.length), eq("laudo"), eq(laudoId), eq(true));
        verify(arquivoService).salvarConteudo(eq(arquivoId), any());
        verify(laudoService).marcarPdf(laudoId, arquivoId);
        verify(laudoService).finalizarComPdfExterno(laudoId, "importado_pleno");
        verify(importacao).marcarPdfBaixado(eq(servicoId), eq(arquivoId), anyString(), eq(laudoId));
        verify(outbox).publish(eq(EventTypes.SIGNATURE_REQUESTED), any());
        verify(importacao).marcarEnviadoParaAssinatura(servicoId);
    }

    @Test
    @DisplayName("rodar 2x nao duplica download, arquivo, laudo, nem republica jobs.signature.send")
    void idempotente_duasChamadas() throws Exception {
        service.ingerir(servicoId, link);
        service.ingerir(servicoId, link);

        verify(httpGateway, times(1)).execute(any(HttpGatewayRequest.class));
        verify(laudoService, times(1)).criar(servicoId);
        verify(arquivoService, times(1)).registrar(anyString(), anyString(), anyLong(), anyString(), any(), eq(true));
        verify(arquivoService, times(1)).salvarConteudo(any(), any());
        verify(outbox, times(1)).publish(eq(EventTypes.SIGNATURE_REQUESTED), any());
        verify(importacao, times(1)).marcarEnviadoParaAssinatura(servicoId);
    }

    @Test
    @DisplayName("PDF ja baixado mas assinatura ainda nao disparada: so publica signature, sem rebaixar")
    void pdfJaBaixado_soDisparaAssinatura() throws Exception {
        row.setPdfArquivoId(arquivoId);
        row.setLaudoId(laudoId);
        row.setPdfSha256("deadbeef");
        row.setStatusImportacao("PDF_BAIXADO");

        service.ingerir(servicoId, link);

        verify(httpGateway, never()).execute(any(HttpGatewayRequest.class));
        verify(laudoService, never()).criar(any());
        verify(arquivoService, never()).registrar(anyString(), anyString(), anyLong(), anyString(), any(), anyBoolean());
        verify(outbox).publish(eq(EventTypes.SIGNATURE_REQUESTED), any());
        verify(importacao).marcarEnviadoParaAssinatura(servicoId);
    }

    @Test
    @DisplayName("ja enviado para assinatura: no-op total")
    void jaEnviado_noOp() throws Exception {
        row.setPdfArquivoId(arquivoId);
        row.setLaudoId(laudoId);
        row.setEnviadoParaAssinaturaEm(Instant.now().minusSeconds(3600));
        row.setStatusImportacao("ENVIADA_PARA_ASSINATURA");

        service.ingerir(servicoId, link);

        verify(httpGateway, never()).execute(any(HttpGatewayRequest.class));
        verify(outbox, never()).publish(anyString(), any());
        verify(importacao, never()).marcarEnviadoParaAssinatura(any());
    }

    @Test
    @DisplayName("laudo criado manualmente antes: reusa em vez de criar outro")
    void reusaLaudoExistente() {
        Laudo existente = new Laudo();
        existente.setId(laudoId);
        existente.setVersao(2);
        when(laudoRepository.findFirstBySolicitacaoServicoIdOrderByVersaoDesc(servicoId))
                .thenReturn(Optional.of(existente));

        service.ingerir(servicoId, link);

        verify(laudoService, never()).criar(any());
        verify(laudoService).marcarPdf(laudoId, arquivoId);
    }
}
