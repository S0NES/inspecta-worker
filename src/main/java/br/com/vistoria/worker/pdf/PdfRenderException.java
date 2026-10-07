package br.com.vistoria.worker.pdf;

/**
 * Raised when HTML could not be turned into a PDF.
 *
 * <p>Unchecked on purpose: {@code PdfConsumer} has no meaningful local recovery, so it lets the
 * exception escape and {@code pdfListenerFactory}'s retry template handles the transient case,
 * with {@code q.pdf.dlq} catching what stays broken.</p>
 */
public class PdfRenderException extends RuntimeException {

    public PdfRenderException(String message) {
        super(message);
    }

    public PdfRenderException(String message, Throwable cause) {
        super(message, cause);
    }
}
