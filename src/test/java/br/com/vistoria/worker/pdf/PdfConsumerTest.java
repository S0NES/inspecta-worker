package br.com.vistoria.worker.pdf;

import br.com.vistoria.core.modules.arquivo.Arquivo;
import br.com.vistoria.core.modules.arquivo.ArquivoService;
import br.com.vistoria.core.modules.laudo.Laudo;
import br.com.vistoria.core.modules.laudo.LaudoService;
import br.com.vistoria.worker.shared.Eventos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PdfConsumer")
class PdfConsumerTest {

    @Mock private LaudoService laudoService;
    @Mock private ArquivoService arquivoService;
    @Mock private PdfRenderer renderer;
    @Mock private LaudoHtmlBuilder htmlBuilder;

    private PdfConsumer consumer;

    private static final String HTML = "<html><body><h1>Laudo</h1></body></html>";

    @BeforeEach
    void setUp() {
        consumer = new PdfConsumer(laudoService, arquivoService, renderer, htmlBuilder);
        // lenient: o teste de replay nunca chega a montar HTML.
        lenient().when(htmlBuilder.html(any())).thenReturn(HTML);
    }

    @Test
    @DisplayName("on renders PDF and saves when laudo has no PDF yet")
    void on_noPdfYet_rendersSavesAndMarks() {
        UUID laudoId = UUID.randomUUID();
        UUID pdfArquivoId = UUID.randomUUID();
        String payload = "{\"laudoId\": \"" + laudoId + "\", \"versao\": 1}";
        Message message = createMessage(payload);

        Laudo laudo = createLaudo(laudoId, 1, null);
        when(laudoService.get(laudoId)).thenReturn(laudo);
        when(renderer.render(any())).thenReturn("pdf bytes".getBytes());

        Arquivo arquivo = new Arquivo();
        arquivo.setId(pdfArquivoId);
        when(arquivoService.registrar(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(arquivo);

        consumer.on(message);

        verify(renderer).render(any(String.class));
        verify(arquivoService).salvarConteudo(pdfArquivoId, "pdf bytes".getBytes());
        verify(laudoService).marcarPdf(laudoId, pdfArquivoId);
    }

    @Test
    @DisplayName("on skips rendering when PDF already exists (idempotency)")
    void on_pdfExists_skipsRendering() {
        UUID laudoId = UUID.randomUUID();
        UUID existingPdfId = UUID.randomUUID();
        String payload = "{\"laudoId\": \"" + laudoId + "\", \"versao\": 1}";
        Message message = createMessage(payload);

        Laudo laudo = createLaudo(laudoId, 1, existingPdfId);
        when(laudoService.get(laudoId)).thenReturn(laudo);

        consumer.on(message);

        verify(renderer, never()).render(any());
        verify(arquivoService, never()).registrar(any(), any(), any(), any(), any(), anyBoolean());
        verify(laudoService, never()).marcarPdf(any(), any());
    }

    @Test
    @DisplayName("on uses versao from message payload")
    void on_withVersionInPayload_usesIt() {
        UUID laudoId = UUID.randomUUID();
        String payload = "{\"laudoId\": \"" + laudoId + "\", \"versao\": 3}";
        Message message = createMessage(payload);

        Laudo laudo = createLaudo(laudoId, 3, null);
        when(laudoService.get(laudoId)).thenReturn(laudo);
        when(renderer.render(any())).thenReturn("pdf".getBytes());

        Arquivo arquivo = new Arquivo();
        arquivo.setId(UUID.randomUUID());
        when(arquivoService.registrar(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(arquivo);

        consumer.on(message);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(arquivoService).registrar(keyCaptor.capture(), any(), any(), any(), any(), anyBoolean());

        assertThat(keyCaptor.getValue()).contains("/v3.pdf");
    }

    @Test
    @DisplayName("on defaults versao to 1 when missing")
    void on_noVersionInPayload_defaultsToOne() {
        UUID laudoId = UUID.randomUUID();
        String payload = "{\"laudoId\": \"" + laudoId + "\"}";
        Message message = createMessage(payload);

        Laudo laudo = createLaudo(laudoId, 1, null);
        when(laudoService.get(laudoId)).thenReturn(laudo);
        when(renderer.render(any())).thenReturn("pdf".getBytes());

        Arquivo arquivo = new Arquivo();
        arquivo.setId(UUID.randomUUID());
        when(arquivoService.registrar(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(arquivo);

        consumer.on(message);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(arquivoService).registrar(keyCaptor.capture(), any(), any(), any(), any(), anyBoolean());

        assertThat(keyCaptor.getValue()).contains("/v1.pdf");
    }

    @Test
    @DisplayName("on registers arquivo with correct metadata")
    void on_registersArquivoWithMetadata() {
        UUID laudoId = UUID.randomUUID();
        String payload = "{\"laudoId\": \"" + laudoId + "\", \"versao\": 1}";
        Message message = createMessage(payload);

        Laudo laudo = createLaudo(laudoId, 1, null);
        when(laudoService.get(laudoId)).thenReturn(laudo);
        when(renderer.render(any())).thenReturn("pdf".getBytes());

        Arquivo arquivo = new Arquivo();
        arquivo.setId(UUID.randomUUID());
        when(arquivoService.registrar(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(arquivo);

        consumer.on(message);

        ArgumentCaptor<String> contentTypeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> sizeCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UUID> resourceIdCaptor = ArgumentCaptor.forClass(UUID.class);

        verify(arquivoService).registrar(any(), contentTypeCaptor.capture(), sizeCaptor.capture(),
            typeCaptor.capture(), resourceIdCaptor.capture(), eq(true));

        assertThat(contentTypeCaptor.getValue()).isEqualTo("application/pdf");
        assertThat(typeCaptor.getValue()).isEqualTo("laudo");
        assertThat(resourceIdCaptor.getValue()).isEqualTo(laudoId);
    }

    @Test
    @DisplayName("on handles renders with LaudoTemplate")
    void on_rendersUsingTemplate() {
        UUID laudoId = UUID.randomUUID();
        String payload = "{\"laudoId\": \"" + laudoId + "\", \"versao\": 1}";
        Message message = createMessage(payload);

        Laudo laudo = createLaudo(laudoId, 1, null);
        when(laudoService.get(laudoId)).thenReturn(laudo);
        when(renderer.render(any())).thenReturn("pdf".getBytes());

        Arquivo arquivo = new Arquivo();
        arquivo.setId(UUID.randomUUID());
        when(arquivoService.registrar(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(arquivo);

        consumer.on(message);

        ArgumentCaptor<String> htmlCaptor = ArgumentCaptor.forClass(String.class);
        verify(renderer).render(htmlCaptor.capture());

        assertThat(htmlCaptor.getValue()).isEqualTo(HTML);
        // O consumer delega a montagem do HTML ao builder, que resolve o template da
        // imobiliaria (ou cai no embutido) - o consumer nao decide isso.
        verify(htmlBuilder).html(laudo);
        verify(laudoService).get(laudoId);
    }

    @Test
    @DisplayName("on creates archivo key with laudoId and version")
    void on_arquivoKeyFormat() {
        UUID laudoId = UUID.randomUUID();
        String payload = "{\"laudoId\": \"" + laudoId + "\", \"versao\": 2}";
        Message message = createMessage(payload);

        Laudo laudo = createLaudo(laudoId, 2, null);
        when(laudoService.get(laudoId)).thenReturn(laudo);
        when(renderer.render(any())).thenReturn("pdf".getBytes());

        Arquivo arquivo = new Arquivo();
        arquivo.setId(UUID.randomUUID());
        when(arquivoService.registrar(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(arquivo);

        consumer.on(message);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(arquivoService).registrar(keyCaptor.capture(), any(), any(), any(), any(), anyBoolean());

        assertThat(keyCaptor.getValue()).isEqualTo("laudos/" + laudoId + "/v2.pdf");
    }

    // Helper methods

    private Message createMessage(String payload) {
        return new Message(payload.getBytes(), new MessageProperties());
    }

    /**
     * Entidade real, nao mock: desde que o consumer delega a montagem do HTML ao
     * {@link LaudoHtmlBuilder}, ele so le pdfArquivoId. Stubar os demais getters passou a
     * disparar UnnecessaryStubbing, e um objeto de verdade e mais simples de ler.
     */
    private Laudo createLaudo(UUID id, int version, UUID pdfArquivoId) {
        Laudo laudo = new Laudo();
        laudo.setId(id);
        laudo.setVersao(version);
        laudo.setPdfArquivoId(pdfArquivoId);
        laudo.setConteudoJson("{\"ambientes\":[]}");
        laudo.setSolicitacaoServicoId(UUID.randomUUID());
        return laudo;
    }
}
