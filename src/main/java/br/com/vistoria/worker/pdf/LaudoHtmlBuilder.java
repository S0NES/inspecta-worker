package br.com.vistoria.worker.pdf;

import br.com.vistoria.core.modules.arquivo.Arquivo;
import br.com.vistoria.core.modules.arquivo.ArquivoService;
import br.com.vistoria.core.modules.imobiliaria.ImobiliariaService;
import br.com.vistoria.core.modules.laudo.Laudo;
import br.com.vistoria.core.modules.pdftemplate.PdfTemplate;
import br.com.vistoria.core.modules.pdftemplate.PdfTemplateService;
import br.com.vistoria.core.modules.publico.AcessoPublicoService;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import freemarker.core.TemplateClassResolver;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateExceptionHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.io.StringWriter;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;

/**
 * Monta o HTML do laudo, preferindo o template cadastrado da imobiliaria.
 *
 * <p>Resolucao em tres niveis, do mais especifico ao mais generico: template da imobiliaria,
 * template global, e por fim {@link LaudoTemplate}, que e codigo. O ultimo nivel importa: sem
 * ele, uma base sem nenhum template cadastrado deixaria de emitir laudo — a geracao de PDF nao
 * pode depender de cadastro.</p>
 *
 * <p>Se um template cadastrado falhar em runtime, o laudo sai pelo embutido em vez de ir para a
 * DLQ. O laudo e um documento com prazo; um template mal formado da imobiliaria nao e motivo
 * para nao emitir. {@code PdfTemplateService} ja valida no save, entao chegar aqui quebrado e
 * excecao, e fica registrado como WARN.</p>
 */
@Component
@Slf4j
public class LaudoHtmlBuilder {

    private static final String CHAVE = "laudo";
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String RODAPE_PADRAO = "Documento gerado pela plataforma Inspecta.";

    private final PdfTemplateService templates;
    private final SolicitacaoService solicitacoes;
    private final ImobiliariaService imobiliarias;
    private final ArquivoService arquivos;
    private final AcessoPublicoService acessosPublicos;
    private final QrCodeGenerator qrCodeGenerator;
    private final String publicBaseUrl;
    private final ObjectMapper mapper;
    private final Configuration freemarker;

    public LaudoHtmlBuilder(PdfTemplateService templates,
                            SolicitacaoService solicitacoes,
                            ImobiliariaService imobiliarias,
                            ArquivoService arquivos,
                            AcessoPublicoService acessosPublicos,
                            QrCodeGenerator qrCodeGenerator,
                            @Value("${inspecta.public.base-url}") String publicBaseUrl,
                            ObjectMapper mapper) {
        this.templates = templates;
        this.solicitacoes = solicitacoes;
        this.imobiliarias = imobiliarias;
        this.arquivos = arquivos;
        this.acessosPublicos = acessosPublicos;
        this.qrCodeGenerator = qrCodeGenerator;
        this.publicBaseUrl = publicBaseUrl;
        this.mapper = mapper;
        this.freemarker = configuracaoRestrita();
    }

    /**
     * Configuracao endurecida: templates sao conteudo editavel por terceiros, entao o motor
     * nao pode alcancar classes Java. ALLOWS_NOTHING_RESOLVER fecha ?new e
     * setAPIBuiltinEnabled(false) fecha ?api — os dois caminhos classicos de template
     * injection. O validador ja recusa ambos no save; isto e a segunda camada, para o caso de
     * uma linha ter entrado no banco por fora do service.
     */
    private static Configuration configuracaoRestrita() {
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_32);
        cfg.setNewBuiltinClassResolver(TemplateClassResolver.ALLOWS_NOTHING_RESOLVER);
        cfg.setAPIBuiltinEnabled(false);
        cfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        cfg.setLogTemplateExceptions(false);
        cfg.setNumberFormat("computer");
        return cfg;
    }

    public String html(Laudo laudo) {
        UUID imobiliariaId = imobiliariaDe(laudo);
        // Calculado uma unica vez: se o template cadastrado falhar e cair no embutido, o
        // fallback abaixo reaproveita o mesmo link/QR em vez de mintar um segundo token
        // para o mesmo laudo.
        GaleriaFotos galeria = galeria(laudo);

        Optional<PdfTemplate> cadastrado =
                templates.resolverParaImobiliaria(CHAVE, imobiliariaId, pdfTemplateIdDaImobiliaria(imobiliariaId));
        if (cadastrado.isEmpty()) {
            return LaudoTemplate.render(laudo, galeria.qrCodeDataUri(), galeria.url());
        }

        PdfTemplate t = cadastrado.get();
        try {
            return renderizar(t, modelo(laudo, imobiliariaId, galeria));
        } catch (Exception e) {
            log.warn("[pdf] template '{}' v{} falhou para laudo {}; emitindo com o template "
                    + "embutido", t.getChave(), t.getVersao(), laudo.getId(), e);
            return LaudoTemplate.render(laudo, galeria.qrCodeDataUri(), galeria.url());
        }
    }

    /**
     * Link publico tipo "imagens" + QR code apontando pra galeria. So emitido se o
     * laudo tem ao menos uma foto; falha em listar/mintar o link nunca derruba o laudo — o
     * PDF sai sem QR, o que e sempre melhor que nao sair (mesma filosofia do fallback de
     * template acima e do nome da imobiliaria em {@link #nomeDaImobiliaria}).
     */
    private GaleriaFotos galeria(Laudo laudo) {
        List<Arquivo> imagens;
        try {
            imagens = arquivos.listarImagensPorDono("laudo", laudo.getId());
        } catch (Exception e) {
            log.warn("[pdf] falha ao listar fotos do laudo {}; QR de galeria omitido",
                    laudo.getId(), e);
            return GaleriaFotos.VAZIA;
        }
        if (imagens.isEmpty()) {
            return GaleriaFotos.VAZIA;
        }
        try {
            String token = acessosPublicos.criarLinkImagens(laudo.getId());
            String url = publicBaseUrl + "/v1/public/imagens/" + token + "/pagina";
            return new GaleriaFotos(qrCodeGenerator.dataUriPng(url), url);
        } catch (Exception e) {
            log.warn("[pdf] falha ao gerar link/QR de galeria do laudo {}; PDF segue sem QR",
                    laudo.getId(), e);
            return GaleriaFotos.VAZIA;
        }
    }

    private record GaleriaFotos(String qrCodeDataUri, String url) {
        static final GaleriaFotos VAZIA = new GaleriaFotos("", "");
    }

    private String renderizar(PdfTemplate t, Map<String, Object> modelo) throws Exception {
        Template ft = new Template("pdf-" + t.getChave() + "-v" + t.getVersao(),
                new StringReader(t.getHtml()), freemarker);
        StringWriter out = new StringWriter();
        ft.process(modelo, out);

        String html = out.toString();
        if (t.getCss() == null || t.getCss().isBlank()) {
            return html;
        }
        // quoteReplacement: o CSS e texto arbitrario e nao pode ser lido como grupo de regex.
        return html.replaceFirst("(?i)</head>",
                Matcher.quoteReplacement("<style>" + t.getCss() + "</style></head>"));
    }

    private UUID imobiliariaDe(Laudo laudo) {
        try {
            return solicitacoes.imobiliariaDoServico(laudo.getSolicitacaoServicoId());
        } catch (Exception e) {
            // Sem tenant o fluxo continua no template global — nao vale derrubar o laudo.
            log.warn("[pdf] imobiliaria do laudo {} nao resolvida; usando template global",
                    laudo.getId(), e);
            return null;
        }
    }

    /**
     * Modelo com <b>todas</b> as chaves do catalogo preenchidas. O FreeMarker quebra em
     * variavel ausente, e o validador garante que o template so use chaves do catalogo — se
     * alguma faltasse aqui, o par validacao/render ficaria inconsistente.
     */
    private Map<String, Object> modelo(Laudo laudo, UUID imobiliariaId, GaleriaFotos galeria) {
        Map<String, Object> m = new HashMap<>();
        m.put("versao", laudo.getVersao());
        m.put("solicitacaoServicoId", laudo.getSolicitacaoServicoId());
        m.put("emitidoEm", LocalDate.now().format(DATA));
        m.put("ambientes", ambientes(laudo));
        m.put("laudoId", laudo.getId());
        m.put("imobiliariaNome", nomeDaImobiliaria(imobiliariaId));
        m.put("observacoesGerais", texto(conteudo(laudo).path("observacoesGerais")));
        m.put("rodape", RODAPE_PADRAO);
        m.put("qrCodeGaleria", galeria.qrCodeDataUri());
        m.put("galeriaUrl", galeria.url());
        return m;
    }

    /**
     * Vinculo explicito de template PDF da imobiliaria, quando houver. Igual a
     * {@link #nomeDaImobiliaria}: falha ao resolver a imobiliaria nunca derruba o laudo — cai
     * na resolucao por chave (template da imobiliaria -> global -> embutido).
     */
    private UUID pdfTemplateIdDaImobiliaria(UUID imobiliariaId) {
        if (imobiliariaId == null) {
            return null;
        }
        try {
            var imob = imobiliarias.findById(imobiliariaId);
            return imob == null ? null : imob.pdfTemplateId();
        } catch (Exception e) {
            log.warn("[pdf] vinculo de template da imobiliaria {} nao resolvido; usando resolucao por chave",
                    imobiliariaId, e);
            return null;
        }
    }

    private String nomeDaImobiliaria(UUID imobiliariaId) {
        if (imobiliariaId == null) {
            return "";
        }
        try {
            return imobiliarias.findById(imobiliariaId).nomeFantasia();
        } catch (Exception e) {
            log.warn("[pdf] nome da imobiliaria {} nao resolvido", imobiliariaId, e);
            return "";
        }
    }

    private JsonNode conteudo(Laudo laudo) {
        try {
            return mapper.readTree(laudo.getConteudoJson());
        } catch (Exception e) {
            throw new IllegalArgumentException("conteudo_json invalido laudo " + laudo.getId(), e);
        }
    }

    /** Converte o JSON do laudo em estruturas simples que o FreeMarker sabe percorrer. */
    private List<Map<String, Object>> ambientes(Laudo laudo) {
        List<Map<String, Object>> lista = new ArrayList<>();
        for (JsonNode amb : conteudo(laudo).path("ambientes")) {
            Map<String, Object> ambiente = new HashMap<>();
            ambiente.put("nome", texto(amb.path("nome"), "Ambiente"));

            List<Map<String, Object>> itens = new ArrayList<>();
            for (JsonNode item : amb.path("itens")) {
                Map<String, Object> i = new HashMap<>();
                i.put("descricao", texto(item.path("descricao")));
                i.put("estado", texto(item.path("estado"), "-"));
                i.put("observacao", texto(item.path("observacao")));
                itens.add(i);
            }
            ambiente.put("itens", itens);
            lista.add(ambiente);
        }
        return lista;
    }

    private static String texto(JsonNode n) {
        return texto(n, "");
    }

    private static String texto(JsonNode n, String padrao) {
        return n == null || n.isMissingNode() || n.isNull() ? padrao : n.asText(padrao);
    }
}
