package br.com.vistoria.worker.pdf;

import br.com.vistoria.core.modules.laudo.Laudo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("LaudoTemplate")
class LaudoTemplateTest {

    private static final UUID FAKE_LAUDO_ID = UUID.randomUUID();
    private static final UUID FAKE_SERVICO_ID = UUID.randomUUID();

    @Test
    @DisplayName("render generates valid HTML with title and version")
    void render_validLaudo_generatesHtml() {
        Laudo laudo = createLaudo(FAKE_LAUDO_ID, 1, FAKE_SERVICO_ID, "{\"ambientes\": []}");

        String html = LaudoTemplate.render(laudo);

        assertThat(html)
            .contains("<!DOCTYPE html>")
            .contains("Laudo de Vistoria")
            .contains("v1")
            .contains("Servico: " + FAKE_SERVICO_ID);
    }

    @Test
    @DisplayName("render escapes HTML special characters in ambiente name")
    void render_htmlSpecialCharsInAmbiente_escaped() {
        Laudo laudo = createLaudo(FAKE_LAUDO_ID, 1, FAKE_SERVICO_ID,
            "{\"ambientes\": [{\"nome\": \"<script>alert('xss')</script>\", \"itens\": []}]}");

        String html = LaudoTemplate.render(laudo);

        assertThat(html)
            .contains("&lt;script&gt;")
            .doesNotContain("<script>");
    }

    @Test
    @DisplayName("render escapes HTML in item description")
    void render_htmlInItemDescription_escaped() {
        Laudo laudo = createLaudo(FAKE_LAUDO_ID, 1, FAKE_SERVICO_ID,
            "{\"ambientes\": [{\"nome\": \"Sala\", \"itens\": [{\"descricao\": \"Parede & teto<br>\", \"estado\": \"Bom\"}]}]}");

        String html = LaudoTemplate.render(laudo);

        assertThat(html)
            .contains("Parede &amp; teto&lt;br&gt;")
            .doesNotContain("Parede & teto<br>");
    }

    @Test
    @DisplayName("render escapes HTML in estado field")
    void render_htmlInEstado_escaped() {
        Laudo laudo = createLaudo(FAKE_LAUDO_ID, 1, FAKE_SERVICO_ID,
            "{\"ambientes\": [{\"nome\": \"Sala\", \"itens\": [{\"descricao\": \"Item\", \"estado\": \"<Bom>\"}]}]}");

        String html = LaudoTemplate.render(laudo);

        assertThat(html)
            .contains("&lt;Bom&gt;")
            .doesNotContain("<Bom>");
    }

    @Test
    @DisplayName("render escapes ampersands properly")
    void render_ampersands_escaped() {
        Laudo laudo = createLaudo(FAKE_LAUDO_ID, 1, FAKE_SERVICO_ID,
            "{\"ambientes\": [{\"nome\": \"A&B\", \"itens\": [{\"descricao\": \"A & B & C\", \"estado\": \"OK\"}]}]}");

        String html = LaudoTemplate.render(laudo);

        assertThat(html)
            .contains("A&amp;B")
            .contains("A &amp; B &amp; C")
            .doesNotContain("A&B")
            .doesNotContain("A & B & C");
    }

    @Test
    @DisplayName("render includes observacao when present")
    void render_withObservacao_included() {
        Laudo laudo = createLaudo(FAKE_LAUDO_ID, 1, FAKE_SERVICO_ID,
            "{\"ambientes\": [{\"nome\": \"Sala\", \"itens\": [{\"descricao\": \"Parede\", \"estado\": \"Bom\", \"observacao\": \"Recomendo pintapintura\"}]}]}");

        String html = LaudoTemplate.render(laudo);

        assertThat(html).contains("Recomendo pintapintura");
    }

    @Test
    @DisplayName("render skips observacao when null")
    void render_withoutObservacao_notIncluded() {
        Laudo laudo = createLaudo(FAKE_LAUDO_ID, 1, FAKE_SERVICO_ID,
            "{\"ambientes\": [{\"nome\": \"Sala\", \"itens\": [{\"descricao\": \"Parede\", \"estado\": \"Bom\"}]}]}");

        String html = LaudoTemplate.render(laudo);

        // Should not fail and should have the basic structure
        assertThat(html)
            .contains("Sala")
            .contains("Parede");
    }

    @Test
    @DisplayName("render handles empty ambientes")
    void render_noAmbientes_staysValid() {
        Laudo laudo = createLaudo(FAKE_LAUDO_ID, 1, FAKE_SERVICO_ID, "{\"ambientes\": []}");

        String html = LaudoTemplate.render(laudo);

        assertThat(html)
            .contains("Laudo de Vistoria")
            .contains("Servico: " + FAKE_SERVICO_ID);
    }

    @Test
    @DisplayName("render throws on invalid JSON")
    void render_invalidJson_throwsException() {
        Laudo laudo = createLaudo(FAKE_LAUDO_ID, 1, FAKE_SERVICO_ID, "not valid json");

        assertThatThrownBy(() -> LaudoTemplate.render(laudo))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("conteudo_json invalido");
    }

    @Test
    @DisplayName("render includes all sections from multiple ambientes")
    void render_multipleAmbientes_allIncluded() {
        Laudo laudo = createLaudo(FAKE_LAUDO_ID, 1, FAKE_SERVICO_ID,
            "{\"ambientes\": [" +
            "{\"nome\": \"Sala\", \"itens\": [{\"descricao\": \"Teto\", \"estado\": \"Bom\"}]}," +
            "{\"nome\": \"Quarto\", \"itens\": [{\"descricao\": \"Janela\", \"estado\": \"OK\"}]}" +
            "]}");

        String html = LaudoTemplate.render(laudo);

        assertThat(html)
            .contains("Sala")
            .contains("Teto")
            .contains("Quarto")
            .contains("Janela");
    }

    @Test
    @DisplayName("render handles missing estado field with default")
    void render_missingEstado_usesDefault() {
        Laudo laudo = createLaudo(FAKE_LAUDO_ID, 1, FAKE_SERVICO_ID,
            "{\"ambientes\": [{\"nome\": \"Sala\", \"itens\": [{\"descricao\": \"Parede\"}]}]}");

        String html = LaudoTemplate.render(laudo);

        assertThat(html)
            .contains("Parede")
            .contains("Estado:</strong> -");
    }

    @Test
    @DisplayName("render handles missing ambiente nome with default")
    void render_missingAmbienteName_usesDefault() {
        Laudo laudo = createLaudo(FAKE_LAUDO_ID, 1, FAKE_SERVICO_ID,
            "{\"ambientes\": [{\"itens\": []}]}");

        String html = LaudoTemplate.render(laudo);

        assertThat(html).contains("Ambiente");
    }

    @Test
    @DisplayName("render includes document metadata footer")
    void render_includesFooter() {
        Laudo laudo = createLaudo(FAKE_LAUDO_ID, 1, FAKE_SERVICO_ID, "{\"ambientes\": []}");

        String html = LaudoTemplate.render(laudo);

        assertThat(html)
            .contains("Documento gerado pela plataforma Inspecta")
            .contains("</html>");
    }

    @Test
    @DisplayName("render(laudo) sem QR nao inclui secao de galeria")
    void render_semQr_omiteGaleria() {
        Laudo laudo = createLaudo(FAKE_LAUDO_ID, 1, FAKE_SERVICO_ID, "{\"ambientes\": []}");

        String html = LaudoTemplate.render(laudo);

        assertThat(html).doesNotContain("Fotos da vistoria").doesNotContain("data:image/png");
    }

    @Test
    @DisplayName("render(laudo, qr, url) embute o QR e o link da galeria")
    void render_comQr_embuteGaleria() {
        Laudo laudo = createLaudo(FAKE_LAUDO_ID, 1, FAKE_SERVICO_ID, "{\"ambientes\": []}");

        String html = LaudoTemplate.render(laudo, "data:image/png;base64,AAA",
                "https://app.inspecta.com.br/v1/public/imagens/tok/pagina");

        assertThat(html)
            .contains("Fotos da vistoria")
            .contains("data:image/png;base64,AAA")
            .contains("https://app.inspecta.com.br/v1/public/imagens/tok/pagina");
    }

    @Test
    @DisplayName("render(laudo, qr, url) escapa a URL exibida como texto")
    void render_comQr_escapaUrl() {
        Laudo laudo = createLaudo(FAKE_LAUDO_ID, 1, FAKE_SERVICO_ID, "{\"ambientes\": []}");

        String html = LaudoTemplate.render(laudo, "data:image/png;base64,AAA",
                "<script>alert(1)</script>");

        assertThat(html).doesNotContain("<script>alert(1)</script>");
    }

    // Helper methods

    private Laudo createLaudo(UUID id, int version, UUID servicoId, String conteudoJson) {
        Laudo laudo = mock(Laudo.class);
        when(laudo.getId()).thenReturn(id);
        when(laudo.getVersao()).thenReturn(version);
        when(laudo.getSolicitacaoServicoId()).thenReturn(servicoId);
        when(laudo.getConteudoJson()).thenReturn(conteudoJson);
        return laudo;
    }
}
