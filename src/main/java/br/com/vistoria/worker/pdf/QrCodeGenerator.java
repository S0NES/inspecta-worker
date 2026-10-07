package br.com.vistoria.worker.pdf;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Map;

/**
 * Gera QR codes como PNG em {@code data:} URI — o unico esquema que {@link PdfRenderer} aceita
 * (ADR-001 / {@code NO_EXTERNAL_RESOURCES}), entao a imagem nunca vira uma referencia externa
 * que o renderizador bloquearia ou que vazaria requisicao pra fora (SSRF).
 */
@Component
public class QrCodeGenerator {

    private static final int TAMANHO_PX = 240;

    public String dataUriPng(String conteudo) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix matrix = new MultiFormatWriter().encode(
                    conteudo, BarcodeFormat.QR_CODE, TAMANHO_PX, TAMANHO_PX, hints);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (WriterException | IOException e) {
            throw new IllegalStateException("falha ao gerar QR code", e);
        }
    }
}
