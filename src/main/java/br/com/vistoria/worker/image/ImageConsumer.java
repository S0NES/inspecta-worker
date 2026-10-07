package br.com.vistoria.worker.image;

import br.com.vistoria.core.modules.arquivo.Arquivo;
import br.com.vistoria.core.modules.arquivo.ArquivoService;
import br.com.vistoria.worker.shared.Eventos;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class ImageConsumer {

    private final ArquivoService arquivoService;

    @RabbitListener(queues = "q.image", containerFactory = "imageListenerFactory")
    public void on(Message message) throws IOException {
        JsonNode e = Eventos.parse(message.getBody());
        UUID arquivoId = UUID.fromString(e.path("arquivoId").asText());

        byte[] original = arquivoService.lerConteudo(arquivoId);

        BufferedImage grande = Thumbnails.of(new ByteArrayInputStream(original))
                .size(1200, 1200).keepAspectRatio(true).useExifOrientation(true)
                .asBufferedImage();
        BufferedImage thumb = Thumbnails.of(new ByteArrayInputStream(original))
                .size(300, 300).crop(net.coobird.thumbnailator.geometry.Positions.CENTER)
                .useExifOrientation(true)
                .asBufferedImage();

        byte[] webpBytes = webp(grande, 0.8f);
        byte[] thumbBytes = webp(thumb, 0.7f);

        arquivoService.salvarConteudo(arquivoId, webpBytes);

        // Create separate arquivo row for thumbnail
        Arquivo thumbArquivo = arquivoService.registrar("thumbnail", "image/webp", (long) thumbBytes.length, "imagem-thumb", null, false);
        arquivoService.salvarConteudo(thumbArquivo.getId(), thumbBytes);
        arquivoService.marcarProcessado(arquivoId, thumbArquivo.getId().toString());

        log.info("imagem {} processada", arquivoId);
    }

    private byte[] webp(BufferedImage img, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByMIMEType("image/webp").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        if (param.canWriteCompressed()) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            String[] types = param.getCompressionTypes();
            if (types != null && types.length > 0) param.setCompressionType(types[0]);
            param.setCompressionQuality(quality);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
