package br.com.vistoria.worker.image;

import br.com.vistoria.core.modules.arquivo.Arquivo;
import br.com.vistoria.core.modules.arquivo.ArquivoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ImageConsumer}.
 *
 * <p>The previous version of this test fed a 4-byte fake JPEG (just the SOI/EOI markers, no
 * actual image data) to the resize/webp pipeline and swallowed whatever exception that threw,
 * so it only ever proved {@code lerConteudo} was called — not that resizing, webp encoding,
 * thumbnail persistence, or the "processado" marker actually happen. These tests instead feed
 * a real in-memory JPEG through {@link ImageIO}, so the full pipeline runs for real and every
 * downstream call can be asserted.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ImageConsumer")
class ImageConsumerTest {

    @Mock private ArquivoService arquivoService;

    private ImageConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ImageConsumer(arquivoService);
    }

    @Test
    @DisplayName("on resizes the original, encodes both sizes as webp, and marks the arquivo processed")
    void on_validImage_resizesEncodesAndMarksProcessed() throws IOException {
        UUID arquivoId = UUID.randomUUID();
        UUID thumbId = UUID.randomUUID();
        String payload = "{\"arquivoId\": \"" + arquivoId + "\"}";
        Message message = createMessage(payload);

        when(arquivoService.lerConteudo(arquivoId)).thenReturn(createRealJpeg(400, 300));

        Arquivo thumbArquivo = new Arquivo();
        thumbArquivo.setId(thumbId);
        when(arquivoService.registrar(eq("thumbnail"), eq("image/webp"), any(), eq("imagem-thumb"), eq(null), eq(false)))
            .thenReturn(thumbArquivo);

        consumer.on(message);

        verify(arquivoService).lerConteudo(arquivoId);

        // The resized "grande" webp overwrites the original arquivo's content.
        ArgumentCaptor<byte[]> largeCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(arquivoService).salvarConteudo(eq(arquivoId), largeCaptor.capture());
        assertThat(largeCaptor.getValue()).isNotEmpty();
        // WEBP inspecta bytes: "RIFF"...."WEBP".
        assertThat(new String(largeCaptor.getValue(), 0, 4)).isEqualTo("RIFF");
        assertThat(new String(largeCaptor.getValue(), 8, 4)).isEqualTo("WEBP");

        // The thumbnail gets its own arquivo row and content.
        ArgumentCaptor<byte[]> thumbCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(arquivoService).salvarConteudo(eq(thumbId), thumbCaptor.capture());
        assertThat(thumbCaptor.getValue()).isNotEmpty();

        verify(arquivoService).marcarProcessado(arquivoId, thumbId.toString());
    }

    @Test
    @DisplayName("on crops the thumbnail to a square regardless of the source aspect ratio")
    void on_wideImage_thumbnailIsSquareCrop() throws IOException {
        UUID arquivoId = UUID.randomUUID();
        UUID thumbId = UUID.randomUUID();
        String payload = "{\"arquivoId\": \"" + arquivoId + "\"}";
        Message message = createMessage(payload);

        // Wide source (800x200) exercises the CENTER crop in the thumbnail pipeline.
        when(arquivoService.lerConteudo(arquivoId)).thenReturn(createRealJpeg(800, 200));

        Arquivo thumbArquivo = new Arquivo();
        thumbArquivo.setId(thumbId);
        when(arquivoService.registrar(eq("thumbnail"), eq("image/webp"), any(), eq("imagem-thumb"), eq(null), eq(false)))
            .thenReturn(thumbArquivo);

        consumer.on(message);

        verify(arquivoService).salvarConteudo(eq(thumbId), any());
        verify(arquivoService).marcarProcessado(arquivoId, thumbId.toString());
    }

    @Test
    @DisplayName("on propagates an exception (does not silently drop the message) when the content isn't a decodable image")
    void on_corruptImageBytes_propagatesException() {
        UUID arquivoId = UUID.randomUUID();
        String payload = "{\"arquivoId\": \"" + arquivoId + "\"}";
        Message message = createMessage(payload);

        // Not an image at all - Thumbnailator/ImageIO must fail to decode this.
        when(arquivoService.lerConteudo(arquivoId)).thenReturn("not an image".getBytes());

        assertThatThrownBy(() -> consumer.on(message))
            .isInstanceOf(IOException.class);

        // Fail-fast: nothing downstream of the failed decode should run, so a retry
        // (imageListenerFactory has T7.6 retry configured) reprocesses cleanly.
        verify(arquivoService, org.mockito.Mockito.never()).salvarConteudo(any(), any());
        verify(arquivoService, org.mockito.Mockito.never()).marcarProcessado(any(), any());
    }

    private Message createMessage(String payload) {
        byte[] body = payload.getBytes();
        MessageProperties props = new MessageProperties();
        props.setReceivedRoutingKey("jobs.image");
        return new Message(body, props);
    }

    /** A real, decodable JPEG of the given size — a solid-color rectangle is enough. */
    private byte[] createRealJpeg(int width, int height) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }
}
