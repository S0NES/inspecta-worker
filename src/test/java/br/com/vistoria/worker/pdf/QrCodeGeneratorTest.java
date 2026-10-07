package br.com.vistoria.worker.pdf;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("QrCodeGenerator")
class QrCodeGeneratorTest {

    private final QrCodeGenerator generator = new QrCodeGenerator();

    @Test
    @DisplayName("gera um data URI PNG valido")
    void dataUriPng_geraDataUriValido() {
        String uri = generator.dataUriPng("https://app.inspecta.com.br/v1/public/imagens/tok/pagina");

        assertThat(uri).startsWith("data:image/png;base64,");
    }

    @Test
    @DisplayName("o QR gerado decodifica de volta pro conteudo original")
    void dataUriPng_decodificaParaConteudoOriginal() throws Exception {
        String conteudo = "https://app.inspecta.com.br/v1/public/imagens/abc123/pagina";
        String uri = generator.dataUriPng(conteudo);

        String base64 = uri.substring("data:image/png;base64,".length());
        byte[] png = Base64.getDecoder().decode(base64);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));

        LuminanceSource source = new BufferedImageLuminanceSource(image);
        Result result = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(source)));

        assertThat(result.getText()).isEqualTo(conteudo);
    }
}
