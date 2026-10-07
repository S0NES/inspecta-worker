package br.com.vistoria.worker.pdf;

import br.com.vistoria.core.modules.laudo.Laudo;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PdfRenderer (OpenHTML2PDF)")
class PdfRendererTest {

    private final PdfRenderer renderer = new PdfRenderer();

    /** Reads the PDF back so assertions are about content, not just byte count. */
    private static String textOf(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private static Laudo laudo(String conteudoJson) {
        Laudo l = new Laudo();
        l.setId(UUID.randomUUID());
        l.setSolicitacaoServicoId(UUID.randomUUID());
        l.setVersao(3);
        l.setConteudoJson(conteudoJson);
        return l;
    }

    @Nested
    @DisplayName("output")
    class Output {

        @Test
        @DisplayName("produces a structurally valid PDF")
        void render_validHtml_producesRealPdf() throws Exception {
            byte[] pdf = renderer.render("<html><body><h1>Relatorio</h1></body></html>");

            assertThat(pdf).startsWith(new byte[] {'%', 'P', 'D', 'F'});
            assertThat(textOf(pdf)).contains("Relatorio");
        }

        @Test
        @DisplayName("preserves pt-BR accented characters")
        void render_accents_survivesEncoding() throws Exception {
            // Unicode escapes, not literal bytes, so the assertion cannot pass or fail because
            // of this source file's encoding. Laudos are pt-BR: accents are not optional.
            String imovel = "im\u00f3vel";                  // imovel
            String comodo = "c\u00f4modo";                  // comodo
            String nao = "n\u00e3o";                        // nao
            String acao = "avalia\u00e7\u00e3o";            // avaliacao

            byte[] pdf = renderer.render("<html><body><p>Vistoria de " + imovel + ": "
                    + comodo + " " + nao + " avariado, " + acao + "</p></body></html>");

            assertThat(textOf(pdf))
                    .contains(imovel)
                    .contains(comodo)
                    .contains(nao)
                    .contains(acao);
        }

        @Test
        @DisplayName("keeps A4 geometry from the injected @page rule")
        void render_defaultGeometry_isA4() throws Exception {
            byte[] pdf = renderer.render("<html><body>x</body></html>");

            try (PDDocument doc = Loader.loadPDF(pdf)) {
                var box = doc.getPage(0).getMediaBox();
                // A4 = 210x297mm = 595x842pt, within a rounding point.
                assertThat(box.getWidth()).isCloseTo(595f, Offset.offset(2f));
                assertThat(box.getHeight()).isCloseTo(842f, Offset.offset(2f));
            }
        }

        @Test
        @DisplayName("honours a template that overrides @page")
        void render_templateOverridesPageSize_wins() throws Exception {
            String html = "<html><head><style>@page { size: A5; margin: 5mm; }</style></head>"
                    + "<body>x</body></html>";

            byte[] pdf = renderer.render(html);

            try (PDDocument doc = Loader.loadPDF(pdf)) {
                // A5 = 420x595pt - proves the injected default is overridable, not hardcoded.
                assertThat(doc.getPage(0).getMediaBox().getWidth()).isCloseTo(420f, Offset.offset(2f));
            }
        }

        @Test
        @DisplayName("paginates long content across multiple pages")
        void render_longContent_spansPages() throws Exception {
            StringBuilder rows = new StringBuilder();
            for (int i = 0; i < 400; i++) {
                rows.append("<p>Item de vistoria numero ").append(i).append("</p>");
            }

            byte[] pdf = renderer.render("<html><body>" + rows + "</body></html>");

            try (PDDocument doc = Loader.loadPDF(pdf)) {
                assertThat(doc.getNumberOfPages()).isGreaterThan(1);
            }
        }
    }

    @Nested
    @DisplayName("malformed input")
    class Malformed {

        @Test
        @DisplayName("repairs unclosed tags instead of failing")
        void render_unclosedTags_stillRenders() throws Exception {
            // Tag soup an editable template can realistically contain. Raw XML parsing would
            // abort here; jsoup normalization is what keeps it renderable.
            byte[] pdf = renderer.render(
                    "<html><body><p>linha um<br><meta charset=utf-8><p>linha dois</body>");

            assertThat(textOf(pdf)).contains("linha um").contains("linha dois");
        }

        @Test
        @DisplayName("renders a bare fragment with no html/body wrapper")
        void render_fragment_stillRenders() throws Exception {
            assertThat(textOf(renderer.render("<h1>solto</h1>"))).contains("solto");
        }

        @Test
        @DisplayName("rejects null html")
        void render_null_throws() {
            assertThatThrownBy(() -> renderer.render(null))
                    .isInstanceOf(PdfRenderException.class)
                    .hasMessageContaining("vazio");
        }

        @Test
        @DisplayName("rejects blank html")
        void render_blank_throws() {
            assertThatThrownBy(() -> renderer.render("   "))
                    .isInstanceOf(PdfRenderException.class)
                    .hasMessageContaining("vazio");
        }
    }

    @Nested
    @DisplayName("external resources (SSRF guard)")
    class ExternalResources {

        @Test
        @DisplayName("blocks http/https/file URIs")
        void resolver_blocksRemoteAndLocalUris() {
            var r = PdfRenderer.NO_EXTERNAL_RESOURCES;

            assertThat(r.resolveURI(null, "http://169.254.169.254/latest/meta-data")).isNull();
            assertThat(r.resolveURI(null, "https://evil.example/x.png")).isNull();
            assertThat(r.resolveURI(null, "file:///etc/passwd")).isNull();
        }

        @Test
        @DisplayName("allows inline data: URIs, case-insensitively")
        void resolver_allowsDataUris() {
            var r = PdfRenderer.NO_EXTERNAL_RESOURCES;
            String dataUri = "data:image/png;base64,iVBORw0KGgo=";

            assertThat(r.resolveURI(null, dataUri)).isEqualTo(dataUri);
            assertThat(r.resolveURI(null, "DATA:image/png;base64,x")).isNotNull();
        }

        @Test
        @DisplayName("a template referencing a remote image still renders")
        void render_remoteImage_doesNotFailTheJob() throws Exception {
            // The point: a blocked resource degrades the page, it does not fail the job.
            byte[] pdf = renderer.render(
                    "<html><body><img src='http://10.0.0.1/logo.png'/><p>conteudo</p></body></html>");

            assertThat(textOf(pdf)).contains("conteudo");
        }
    }

    @Nested
    @DisplayName("concurrency")
    class Concurrency {

        @Test
        @DisplayName("renders correctly from parallel threads")
        void render_concurrent_isThreadSafe() throws Exception {
            // Playwright forced concurrency=1; this test is the evidence that the constraint
            // is gone and q.pdf may run more than one consumer.
            int n = 8;
            ExecutorService pool = Executors.newFixedThreadPool(n);
            try {
                List<Callable<byte[]>> jobs = IntStream.range(0, n)
                        .<Callable<byte[]>>mapToObj(i -> () ->
                                renderer.render("<html><body><p>doc-" + i + "</p></body></html>"))
                        .toList();

                List<Future<byte[]>> results = pool.invokeAll(jobs);

                for (int i = 0; i < n; i++) {
                    byte[] pdf = results.get(i).get();
                    assertThat(pdf).startsWith(new byte[] {'%', 'P', 'D', 'F'});
                    assertThat(textOf(pdf)).contains("doc-" + i);
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Nested
    @DisplayName("laudo regression")
    class LaudoRegression {

        @Test
        @DisplayName("renders the real LaudoTemplate output end to end")
        void render_actualLaudoTemplate_containsLaudoData() throws Exception {
            // Guards the swap itself: the template that works in production today must keep
            // producing the same readable content under the new engine.
            String conteudo = "{\"ambientes\":["
                    + "{\"nome\":\"Sala\",\"itens\":["
                    + "{\"descricao\":\"Piso laminado\",\"estado\":\"bom\",\"observacao\":\"risco leve\"},"
                    + "{\"descricao\":\"Parede norte\",\"estado\":\"otimo\"}]},"
                    + "{\"nome\":\"Cozinha\",\"itens\":["
                    + "{\"descricao\":\"Bancada\",\"estado\":\"regular\"}]}]}";

            String text = textOf(renderer.render(LaudoTemplate.render(laudo(conteudo))));

            assertThat(text)
                    .contains("Laudo de Vistoria")
                    .contains("Sala")
                    .contains("Piso laminado")
                    .contains("risco leve")
                    .contains("Cozinha")
                    .contains("Bancada");
        }

        @Test
        @DisplayName("renders a laudo with no ambientes")
        void render_emptyLaudo_stillProducesPdf() throws Exception {
            byte[] pdf = renderer.render(LaudoTemplate.render(laudo("{\"ambientes\":[]}")));

            assertThat(textOf(pdf)).contains("Laudo de Vistoria");
        }

        @Test
        @DisplayName("escapes HTML injected through laudo content")
        void render_htmlInContent_isEscapedNotExecuted() throws Exception {
            String conteudo = "{\"ambientes\":[{\"nome\":\"<script>alert(1)</script>\",\"itens\":[]}]}";

            String text = textOf(renderer.render(LaudoTemplate.render(laudo(conteudo))));

            // LaudoTemplate.esc() escapes it, so it must survive as literal text.
            assertThat(text).contains("script");
        }
    }
}
