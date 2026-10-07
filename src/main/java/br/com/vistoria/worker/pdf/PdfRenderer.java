package br.com.vistoria.worker.pdf;

import com.openhtmltopdf.extend.FSUriResolver;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Entities;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;

/**
 * Renders HTML to PDF in-process with OpenHTML2PDF (Flying Saucer + PDFBox).
 *
 * <h3>Why not Playwright</h3>
 * <p>The previous implementation drove a headless Chromium. That pinned {@code inspecta-worker} to
 * {@code replicas: 1} (the image carries a full browser), forced {@code q.pdf} to
 * {@code concurrency=1} (the Playwright Java API is not thread-safe), and pulled ~400 MB of
 * browser binaries into the image. Laudo markup is static, single-pass HTML with no scripting,
 * so a browser was never needed. See {@code docs/adr/ADR-001-pdf-engine.md}.</p>
 *
 * <h3>Thread safety</h3>
 * <p>This class holds no mutable state: every call builds its own renderer and stream, so it is
 * safe to call concurrently. That is what allows {@code replicas: 2} and a {@code q.pdf}
 * concurrency above 1.</p>
 *
 * <h3>Well-formedness</h3>
 * <p>OpenHTML2PDF consumes XML, not tag-soup HTML — an unclosed {@code <br>} or {@code <meta>}
 * aborts the render. Since laudo templates are tenant-editable, input is first normalized through
 * jsoup, which repairs malformed markup into well-formed XHTML.</p>
 *
 * <h3>Page geometry</h3>
 * <p>Playwright took format and margins as API options; here they are CSS. A default
 * {@code @page} rule reproducing the previous geometry (A4, 20mm vertical / 15mm horizontal) is
 * injected as the <em>first</em> stylesheet, so a template may still override it.</p>
 */
@Component
public class PdfRenderer {

    /** Reproduces the geometry the Playwright implementation requested via PdfOptions. */
    static final String DEFAULT_PAGE_CSS =
            "@page { size: A4; margin: 20mm 15mm; }";

    /**
     * Blocks every external fetch. Templates are tenant-editable, so an {@code <img src=...>} or
     * {@code @import} would otherwise let a tenant make the worker issue arbitrary requests from
     * inside the private network (SSRF), or read local files via {@code file:}. Only inline
     * {@code data:} URIs resolve.
     */
    static final FSUriResolver NO_EXTERNAL_RESOURCES = (baseUri, uri) -> {
        if (uri != null && uri.regionMatches(true, 0, "data:", 0, 5)) {
            return uri;
        }
        return null;
    };

    public byte[] render(String html) {
        if (html == null || html.isBlank()) {
            throw new PdfRenderException("html vazio: nada para renderizar");
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.useUriResolver(NO_EXTERNAL_RESOURCES);
            builder.withW3cDocument(new W3CDom().fromJsoup(normalize(html)), null);
            builder.toStream(out);
            builder.run();

            byte[] pdf = out.toByteArray();
            if (pdf.length == 0) {
                throw new PdfRenderException("renderizacao produziu 0 bytes");
            }
            return pdf;
        } catch (PdfRenderException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfRenderException("falha ao renderizar PDF", e);
        }
    }

    /** Repairs tag soup into well-formed XHTML and guarantees a default {@code @page} rule. */
    private static Document normalize(String html) {
        Document doc = Jsoup.parse(html);
        doc.outputSettings()
                .syntax(Document.OutputSettings.Syntax.xml)
                .escapeMode(Entities.EscapeMode.xhtml)
                .charset("UTF-8");
        doc.head().prependElement("style").attr("type", "text/css").text(DEFAULT_PAGE_CSS);
        return doc;
    }
}
