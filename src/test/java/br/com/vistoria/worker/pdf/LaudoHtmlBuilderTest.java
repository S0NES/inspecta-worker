package br.com.vistoria.worker.pdf;

import br.com.vistoria.core.modules.arquivo.Arquivo;
import br.com.vistoria.core.modules.arquivo.ArquivoService;
import br.com.vistoria.core.modules.imobiliaria.ImobiliariaService;
import br.com.vistoria.core.modules.imobiliaria.dto.ImobiliariaResponse;
import br.com.vistoria.core.modules.laudo.Laudo;
import br.com.vistoria.core.modules.pdftemplate.PdfTemplate;
import br.com.vistoria.core.modules.pdftemplate.PdfTemplateService;
import br.com.vistoria.core.modules.publico.AcessoPublicoService;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LaudoHtmlBuilder")
class LaudoHtmlBuilderTest {

    @Mock private PdfTemplateService templates;
    @Mock private SolicitacaoService solicitacoes;
    @Mock private ImobiliariaService imobiliarias;
    @Mock private ArquivoService arquivos;
    @Mock private AcessoPublicoService acessosPublicos;
    @Mock private QrCodeGenerator qrCodeGenerator;

    private LaudoHtmlBuilder builder;

    private static final UUID LAUDO_ID = UUID.randomUUID();
    private static final UUID SERVICO_ID = UUID.randomUUID();
    private static final UUID IMOBILIARIA_ID = UUID.randomUUID();
    private static final UUID FOTO_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Nenhum teste desta classe stuba arquivos.listarImagensPorDono: o mock devolve lista
        // vazia por padrao, entao o QR nunca entra em jogo aqui -- ele tem sua propria classe
        // de teste (galeria de fotos).
        builder = new LaudoHtmlBuilder(templates, solicitacoes, imobiliarias, arquivos,
                acessosPublicos, qrCodeGenerator, "https://app.inspecta.com.br", new ObjectMapper());
    }

    private static Laudo laudo() {
        Laudo l = new Laudo();
        l.setId(LAUDO_ID);
        l.setSolicitacaoServicoId(SERVICO_ID);
        l.setVersao(3);
        l.setConteudoJson("""
                {"observacoesGerais":"tudo certo","ambientes":[
                  {"nome":"Sala","itens":[
                    {"descricao":"Piso","estado":"bom","observacao":"risco leve"}]}]}""");
        return l;
    }

    private static PdfTemplate template(String html, String css) {
        PdfTemplate t = new PdfTemplate();
        t.setChave("laudo");
        t.setNome("Template da imobiliaria");
        t.setVersao(2);
        t.setImobiliariaId(IMOBILIARIA_ID);
        t.setHtml(html);
        t.setCss(css);
        return t;
    }

    private static ImobiliariaResponse imob() {
        return new ImobiliariaResponse(IMOBILIARIA_ID, "Imob LTDA", "Imob", "00", true);
    }

    @Nested
    @DisplayName("resolucao")
    class Resolucao {

        @Test
        @DisplayName("sem template cadastrado usa o embutido em codigo")
        void html_semTemplate_usaEmbutido() {
            // Sem este fallback, uma base sem cadastro deixaria de emitir laudo.
            when(solicitacoes.imobiliariaDoServico(SERVICO_ID)).thenReturn(IMOBILIARIA_ID);
            when(templates.resolverParaImobiliaria("laudo", IMOBILIARIA_ID, null)).thenReturn(Optional.empty());

            String html = builder.html(laudo());

            assertThat(html).contains("Laudo de Vistoria").contains("Sala").contains("Piso");
        }

        @Test
        @DisplayName("usa o template cadastrado quando existe")
        void html_comTemplate_usaCadastrado() {
            when(solicitacoes.imobiliariaDoServico(SERVICO_ID)).thenReturn(IMOBILIARIA_ID);
            when(imobiliarias.findById(IMOBILIARIA_ID)).thenReturn(imob());
            when(templates.resolverParaImobiliaria("laudo", IMOBILIARIA_ID, null)).thenReturn(Optional.of(template("""
                    <html><head></head><body>
                      <h1>${imobiliariaNome} - laudo v${versao}</h1>
                      <p>servico ${solicitacaoServicoId} em ${emitidoEm}</p>
                      <#list ambientes as amb><h2>${amb.nome}</h2>
                        <#list amb.itens as i><p>${i.descricao}: ${i.estado}</p></#list>
                      </#list>
                      <footer>${rodape}</footer>
                    </body></html>""", null)));

            String html = builder.html(laudo());

            assertThat(html)
                    .contains("Imob - laudo v3")
                    .contains("servico " + SERVICO_ID)
                    .contains("Sala")
                    .contains("Piso: bom")
                    .doesNotContain("Laudo de Vistoria");
        }

        @Test
        @DisplayName("tenant nao resolvido cai no template global")
        void html_semImobiliaria_resolveGlobal() {
            when(solicitacoes.imobiliariaDoServico(SERVICO_ID))
                    .thenThrow(new IllegalStateException("sem solicitacao"));
            when(templates.resolverParaImobiliaria("laudo", null, null)).thenReturn(Optional.empty());

            String html = builder.html(laudo());

            assertThat(html).contains("Laudo de Vistoria");
        }
    }

    @Nested
    @DisplayName("css")
    class Css {

        @Test
        @DisplayName("injeta o css do template no head")
        void html_comCss_injetaNoHead() {
            when(solicitacoes.imobiliariaDoServico(SERVICO_ID)).thenReturn(null);
            when(templates.resolverParaImobiliaria("laudo", null, null)).thenReturn(Optional.of(template(
                    "<html><head></head><body><p>${versao}</p></body></html>",
                    "body { color: #1e5a96; }")));

            String html = builder.html(laudo());

            assertThat(html).contains("<style>body { color: #1e5a96; }</style></head>");
        }

        @Test
        @DisplayName("css com cifrao nao e interpretado como grupo de regex")
        void html_cssComCifrao_naoQuebra() {
            when(solicitacoes.imobiliariaDoServico(SERVICO_ID)).thenReturn(null);
            when(templates.resolverParaImobiliaria("laudo", null, null)).thenReturn(Optional.of(template(
                    "<html><head></head><body><p>${versao}</p></body></html>",
                    "body:after { content: \"$1 R$\"; }")));

            String html = builder.html(laudo());

            assertThat(html).contains("content: \"$1 R$\"");
        }
    }

    @Nested
    @DisplayName("robustez")
    class Robustez {

        @Test
        @DisplayName("template quebrado em runtime nao derruba o laudo")
        void html_templateQuebrado_caiNoEmbutido() {
            // O laudo tem prazo: template ruim da imobiliaria nao pode virar DLQ.
            when(solicitacoes.imobiliariaDoServico(SERVICO_ID)).thenReturn(IMOBILIARIA_ID);
            lenient().when(imobiliarias.findById(any())).thenReturn(imob());
            when(templates.resolverParaImobiliaria("laudo", IMOBILIARIA_ID, null)).thenReturn(Optional.of(template(
                    "<html><body>${campoQueNaoExiste}</body></html>", null)));

            String html = builder.html(laudo());

            assertThat(html).contains("Laudo de Vistoria");
        }

        @Test
        @DisplayName("builtin ?new e bloqueado no render (segunda camada de defesa)")
        void html_builtinNew_bloqueado() {
            // O validador ja recusa no save; aqui garante-se que uma linha que entrou por fora
            // do service tambem nao executa codigo.
            when(solicitacoes.imobiliariaDoServico(SERVICO_ID)).thenReturn(null);
            when(templates.resolverParaImobiliaria("laudo", null, null)).thenReturn(Optional.of(template(
                    "<html><body>${\"freemarker.template.utility.Execute\"?new()(\"id\")}</body></html>",
                    null)));

            String html = builder.html(laudo());

            assertThat(html).contains("Laudo de Vistoria");
        }

        @Test
        @DisplayName("nome da imobiliaria indisponivel nao impede o render")
        void html_nomeIndisponivel_renderizaVazio() {
            when(solicitacoes.imobiliariaDoServico(SERVICO_ID)).thenReturn(IMOBILIARIA_ID);
            when(imobiliarias.findById(IMOBILIARIA_ID)).thenThrow(new IllegalStateException("fora do ar"));
            when(templates.resolverParaImobiliaria("laudo", IMOBILIARIA_ID, null)).thenReturn(Optional.of(template(
                    "<html><body><h1>[${imobiliariaNome}] v${versao}</h1>"
                    + "<p>${solicitacaoServicoId} ${emitidoEm}</p>"
                    + "<#list ambientes as a>${a.nome}</#list></body></html>", null)));

            String html = builder.html(laudo());

            assertThat(html).contains("[] v3").contains("Sala");
        }

        @Test
        @DisplayName("observacoesGerais chega do conteudo do laudo")
        void html_observacoes_disponiveisNoModelo() {
            when(solicitacoes.imobiliariaDoServico(SERVICO_ID)).thenReturn(null);
            when(templates.resolverParaImobiliaria("laudo", null, null)).thenReturn(Optional.of(template(
                    "<html><body><h1>v${versao}</h1>"
                    + "<p>${solicitacaoServicoId} ${emitidoEm}</p>"
                    + "<#list ambientes as a>${a.nome}</#list>"
                    + "<p>${observacoesGerais}</p></body></html>", null)));

            String html = builder.html(laudo());

            assertThat(html).contains("tudo certo");
        }
    }

    @Nested
    @DisplayName("galeria de fotos (QR, T2.10)")
    class GaleriaDeFotos {

        @BeforeEach
        void semTemplateCadastradoPorPadrao() {
            lenient().when(solicitacoes.imobiliariaDoServico(SERVICO_ID)).thenReturn(null);
            lenient().when(templates.resolverParaImobiliaria("laudo", null, null)).thenReturn(Optional.empty());
        }

        private static Arquivo foto(String mime) {
            Arquivo a = new Arquivo();
            a.setId(FOTO_ID);
            a.setMime(mime);
            return a;
        }

        @Test
        @DisplayName("sem fotos, PDF sai sem secao de galeria e sem mintar link publico")
        void html_semFotos_omiteGaleria() {
            when(arquivos.listarImagensPorDono("laudo", LAUDO_ID)).thenReturn(List.of());

            String html = builder.html(laudo());

            assertThat(html).doesNotContain("data:image/png");
            verify(acessosPublicos, never()).criarLinkImagens(any());
        }

        @Test
        @DisplayName("com fotos, embute o QR e o link da galeria no template embutido")
        void html_comFotos_embuteQr() {
            when(arquivos.listarImagensPorDono("laudo", LAUDO_ID)).thenReturn(List.of(foto("image/jpeg")));
            when(acessosPublicos.criarLinkImagens(LAUDO_ID)).thenReturn("tok123");
            when(qrCodeGenerator.dataUriPng("https://app.inspecta.com.br/v1/public/imagens/tok123/pagina"))
                    .thenReturn("data:image/png;base64,AAA");

            String html = builder.html(laudo());

            assertThat(html)
                    .contains("data:image/png;base64,AAA")
                    .contains("https://app.inspecta.com.br/v1/public/imagens/tok123/pagina");
        }

        @Test
        @DisplayName("falha ao listar fotos nao derruba o laudo")
        void html_falhaAoListarFotos_pdfSaiSemQr() {
            when(arquivos.listarImagensPorDono("laudo", LAUDO_ID)).thenThrow(new RuntimeException("db fora"));

            String html = builder.html(laudo());

            assertThat(html).contains("Laudo de Vistoria");
            verify(acessosPublicos, never()).criarLinkImagens(any());
        }

        @Test
        @DisplayName("falha ao gerar o link publico nao derruba o laudo")
        void html_falhaAoMintarLink_pdfSaiSemQr() {
            when(arquivos.listarImagensPorDono("laudo", LAUDO_ID)).thenReturn(List.of(foto("image/jpeg")));
            when(acessosPublicos.criarLinkImagens(LAUDO_ID)).thenThrow(new RuntimeException("token fora"));

            String html = builder.html(laudo());

            assertThat(html).contains("Laudo de Vistoria").doesNotContain("data:image/png");
        }

        @Test
        @DisplayName("template cadastrado que referencia qrCodeGaleria/galeriaUrl recebe os dois")
        void html_templateCadastrado_recebeQrNoModelo() {
            when(solicitacoes.imobiliariaDoServico(SERVICO_ID)).thenReturn(IMOBILIARIA_ID);
            lenient().when(imobiliarias.findById(IMOBILIARIA_ID)).thenReturn(imob());
            when(arquivos.listarImagensPorDono("laudo", LAUDO_ID)).thenReturn(List.of(foto("image/png")));
            when(acessosPublicos.criarLinkImagens(LAUDO_ID)).thenReturn("tokABC");
            when(qrCodeGenerator.dataUriPng(any())).thenReturn("data:image/png;base64,ZZZ");
            when(templates.resolverParaImobiliaria("laudo", IMOBILIARIA_ID, null)).thenReturn(Optional.of(template(
                    "<html><body><#if qrCodeGaleria?has_content>"
                            + "<img src=\"${qrCodeGaleria}\"/><p>${galeriaUrl}</p></#if></body></html>",
                    null)));

            String html = builder.html(laudo());

            assertThat(html)
                    .contains("data:image/png;base64,ZZZ")
                    .contains("https://app.inspecta.com.br/v1/public/imagens/tokABC/pagina");
        }

        @Test
        @DisplayName("template quebrado com fotos reaproveita o mesmo link no fallback (nao minta dois tokens)")
        void html_templateQuebradoComFotos_naoMintaDoisTokens() {
            when(solicitacoes.imobiliariaDoServico(SERVICO_ID)).thenReturn(IMOBILIARIA_ID);
            lenient().when(imobiliarias.findById(IMOBILIARIA_ID)).thenReturn(imob());
            when(arquivos.listarImagensPorDono("laudo", LAUDO_ID)).thenReturn(List.of(foto("image/png")));
            when(acessosPublicos.criarLinkImagens(LAUDO_ID)).thenReturn("tokUNICO");
            when(qrCodeGenerator.dataUriPng(any())).thenReturn("data:image/png;base64,YYY");
            when(templates.resolverParaImobiliaria("laudo", IMOBILIARIA_ID, null)).thenReturn(Optional.of(template(
                    "<html><body>${campoQueNaoExiste}</body></html>", null)));

            String html = builder.html(laudo());

            assertThat(html).contains("Laudo de Vistoria").contains("data:image/png;base64,YYY");
            verify(acessosPublicos, times(1)).criarLinkImagens(LAUDO_ID);
        }
    }
}
