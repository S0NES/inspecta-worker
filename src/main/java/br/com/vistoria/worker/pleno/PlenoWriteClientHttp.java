package br.com.vistoria.worker.pleno;

import br.com.vistoria.core.gateway.HttpGateway;
import br.com.vistoria.core.gateway.HttpGatewayRequest;
import br.com.vistoria.core.gateway.HttpGatewayResponse;
import br.com.vistoria.core.modules.cadastro.CadastroRepositories.ImovelRepository;
import br.com.vistoria.core.modules.cadastro.CadastroRepositories.ServicoTipoRepository;
import br.com.vistoria.core.modules.cadastro.CadastroRepositories.TipoServicoRepository;
import br.com.vistoria.core.modules.cadastro.Imovel;
import br.com.vistoria.core.modules.cadastro.ServicoTipo;
import br.com.vistoria.core.modules.cadastro.TipoServico;
import br.com.vistoria.core.modules.disponibilidade.DisponibilidadeRepositories.VistoriadorRepository;
import br.com.vistoria.core.modules.disponibilidade.Vistoriador;
import br.com.vistoria.core.modules.imobiliaria.Imobiliaria;
import br.com.vistoria.core.modules.imobiliaria.ImobiliariaRepository;
import br.com.vistoria.core.modules.integracao.IntegracaoSync;
import br.com.vistoria.core.modules.notify.NotifyTemplates;
import br.com.vistoria.core.modules.integracao.IntegracaoSyncService;
import br.com.vistoria.core.modules.integracao.PlenoAuthService;
import br.com.vistoria.core.modules.integracao.PlenoPessoaGarantia;
import br.com.vistoria.core.modules.integracao.PlenoPessoaGarantiaRepository;
import br.com.vistoria.core.modules.integracao.PlenoTokenStore;
import br.com.vistoria.core.modules.solicitacao.Solicitacao;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoRepositories.ServicoRepository;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoRepositories.SolicitacaoRepository;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoRepositories.SolicitacaoIntervenienteRepository;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoInterveniente;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoServico;
import br.com.vistoria.core.modules.usuario.Usuario;
import br.com.vistoria.core.modules.usuario.UsuarioImobiliariaRepository;
import br.com.vistoria.core.modules.usuario.UsuarioRepository;
import br.com.vistoria.core.shared.outbox.OutboxService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Espelhamento de escrita na Pleno Sistemas (T6.15, spec {@code fases/fase-6-pleno.md}).
 *
 * <p>Roda somente no {@code inspecta-worker} (regra 1 de {@code arquitetura.md}): o inspecta-api nunca
 * chama a API de escrita externa. Entra por {@code q.pleno} {@code acao=espelhar} via
 * {@link PlenoSyncConsumer}.</p>
 *
 * <p>DAG de dependencias {@code imobiliaria -> {imovel, vistoriador} -> solicitacao}: se uma
 * dependencia ainda nao tem {@code external_id}/{@code pleno_id}, este metodo reenfileira a
 * dependencia ({@link IntegracaoSyncService#enfileirar}) e lanca {@link IllegalStateException} —
 * o consumidor marca {@code erro} e o backoff + {@link PlenoSyncCron} reprocessam quando ela
 * resolver. Nunca cria a dependencia inline nem manda o {@code store} do filho com ID faltando.</p>
 *
 * <p>Envelope Pleno: {@code HTTP 2xx} nao e sucesso — so {@code success === true}. Erro de
 * validacao = {@code HTTP 4xx} + {@code success:false} + {@code error.message}. Os {@code store}
 * de pessoa/cliente/imovel/usuario vao como {@code application/x-www-form-urlencoded} (arrays
 * aninhados em notacao PHP {@code chave[i][sub]}); o {@code solicitacao/store} vai como
 * {@code multipart/form-data} ({@link #postMultipart}) — confirmado com a Pleno. Precisa de
 * teste contra o endpoint real (urlencoded pode funcionar tambem).</p>
 */
@Component("plenoWriteHttp")
@Slf4j
public class PlenoWriteClientHttp implements PlenoWriteClient {

    private static final DateTimeFormatter AGENDAMENTO = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");

    private final PlenoTokenStore tokenStore;
    private final PlenoAuthService authService;
    private final HttpGateway httpGateway;
    private final IntegracaoSyncService integracaoSync;
    private final ImobiliariaRepository imobiliarias;
    private final ImovelRepository imoveis;
    private final SolicitacaoIntervenienteRepository intervenientes;
    private final VistoriadorRepository vistoriadores;
    private final UsuarioRepository usuarios;
    private final SolicitacaoRepository solicitacoes;
    private final ServicoRepository servicos;
    private final TipoServicoRepository tiposServico;
    private final ServicoTipoRepository servicoTipos;
    private final PlenoPessoaGarantiaRepository pessoaGarantia;
    private final UsuarioImobiliariaRepository usuarioImobiliarias;
    private final OutboxService outbox;

    private final ObjectMapper mapper = new ObjectMapper();

    /** {@code ", 12" / " 12A"} no fim de um endereco livre -> {@code imo_numero} (4.4). */
    private static final Pattern NUMERO_ENDERECO = Pattern.compile(",?\\s*(\\d+[A-Za-z]?)\\s*$");

    @Value("${inspecta.pleno.base-url:}") private String baseUrl;

    // De-para Pleno externalizavel (T6.16, inspecta.pleno.depara.*): os defaults abaixo sao os
    // mesmos valores do InspectaSystem v1. Sao repetidos como inicializador de campo para que os
    // testes unitarios puros (sem Spring processar @Value) tambem peguem o comportamento correto.
    // Mapas de de-para vao como listas "chave:valor" separadas por virgula (mesmo estilo de
    // vistoriador-sistemas) e sao parseados em Map por pares() — evita SpEL/YAML fragil.
    // tip_imo_codigo VARIA POR TENANT (Q6/Q9) — revisar por ambiente antes de por em prod.
    @Value("${inspecta.pleno.depara.tip-imo-codigo:36}") private int tipImoCodigo = 36;
    /** imoveis.tipo (substring normalizada) -> tip_imo_codigo. {@code casa->14}; apartamento NAO confirmado. */
    @Value("${inspecta.pleno.depara.tip-imo-por-tipo:casa:14}")
    private List<String> tipImoPorTipo = List.of("casa:14");
    /** tip_vis_codigo default: entrada=2, saida=6 (4.1). Conferencia NAO documentado — confirmar
     *  via {@code GET /api/vistoria/tipoVistoria} no tenant alvo. */
    @Value("${inspecta.pleno.depara.tip-vis-codigo:2}") private int tipVisCodigo = 2;
    /** substring do tipos_servico.nome normalizado -> tip_vis_codigo. */
    @Value("${inspecta.pleno.depara.tip-vis-por-nome:entrada:2,saida:6}")
    private List<String> tipVisPorNome = List.of("entrada:2", "saida:6");
    /** gru_codigo do vistoriador (15 = Vistoriador). Confirmar — nao ha endpoint de grupos documentado. */
    @Value("${inspecta.pleno.depara.gru-codigo-vistoriador:15}") private int gruCodigoVistoriador = 15;
    /** usuario/adicionar: usu_codigo_modelo (parametrizacao/modelos de vistoria). Mandado so se != vazio.
     *  Confirmar o valor no tenant. */
    @Value("${inspecta.pleno.depara.usu-codigo-modelo:}") private String usuCodigoModelo = "";
    @Value("${inspecta.pleno.depara.cli-contestacao-dias:5}") private int cliContestacaoDias = 5;
    /** intervenientes.papel -> tip_fun_codigo (3=Fiador, 4=Locatario, 5=Locador, 6=Testemunha). */
    @Value("${inspecta.pleno.depara.tip-fun-por-papel:locador:5,locatario:4,fiador:3,testemunha:6}")
    private List<String> tipFunPorPapel = List.of("locador:5", "locatario:4", "fiador:3", "testemunha:6");
    /** usuario/adicionar: valores de {@code sistemas[]} que o vistoriador ganha na Pleno. */
    @Value("${inspecta.pleno.depara.vistoriador-sistemas:6,3}") private List<String> vistoriadorSistemas = List.of("6", "3");

    public PlenoWriteClientHttp(PlenoTokenStore tokenStore, PlenoAuthService authService, HttpGateway httpGateway,
                                IntegracaoSyncService integracaoSync, ImobiliariaRepository imobiliarias,
                                ImovelRepository imoveis, SolicitacaoIntervenienteRepository intervenientes,
                                VistoriadorRepository vistoriadores,
                                UsuarioRepository usuarios, SolicitacaoRepository solicitacoes,
                                ServicoRepository servicos, TipoServicoRepository tiposServico,
                                ServicoTipoRepository servicoTipos,
                                PlenoPessoaGarantiaRepository pessoaGarantia,
                                UsuarioImobiliariaRepository usuarioImobiliarias, OutboxService outbox) {
        this.tokenStore = tokenStore;
        this.authService = authService;
        this.httpGateway = httpGateway;
        this.integracaoSync = integracaoSync;
        this.imobiliarias = imobiliarias;
        this.imoveis = imoveis;
        this.intervenientes = intervenientes;
        this.vistoriadores = vistoriadores;
        this.usuarios = usuarios;
        this.solicitacoes = solicitacoes;
        this.servicos = servicos;
        this.tiposServico = tiposServico;
        this.servicoTipos = servicoTipos;
        this.pessoaGarantia = pessoaGarantia;
        this.usuarioImobiliarias = usuarioImobiliarias;
        this.outbox = outbox;
    }

    private String token() {
        String t = tokenStore.get();
        if (t == null) {
            authService.renovar();
            t = tokenStore.get();
        }
        if (t == null) throw new IllegalStateException("Token Pleno indisponivel — auth de integrador falhou ou credenciais nao configuradas");
        return t;
    }

    @Override
    @Transactional
    public WriteResult espelhar(String recursoTipo, UUID recursoId) {
        token();
        return switch (recursoTipo) {
            case "imobiliaria" -> espelharImobiliaria(recursoId);
            case "vistoriador" -> espelharVistoriador(recursoId);
            case "imovel" -> espelharImovel(recursoId);
            case "solicitacao" -> espelharSolicitacao(recursoId);
            default -> throw new IllegalStateException("recurso_tipo nao espelhavel na Pleno: " + recursoTipo);
        };
    }


    private WriteResult espelharImobiliaria(UUID id) {
        Imobiliaria im = imobiliarias.findById(id)
                .orElseThrow(() -> new IllegalStateException("imobiliaria " + id + " nao encontrada"));
        String cnpj = digitos(im.getCnpj());
        if (cnpj.length() != 14) throw new IllegalStateException("imobiliaria " + id + ": CNPJ invalido");
        if (im.getRazaoSocial() == null || im.getRazaoSocial().isBlank())
            throw new IllegalStateException("imobiliaria " + id + ": razao_social vazia");
        if (!im.isAtiva()) throw new IllegalStateException("imobiliaria " + id + ": inativa, nao espelha");

        // Idempotencia: pessoa/cliente de garantia ja criados nesta imobiliaria?
        var jaGarantido = pessoaGarantia.findByImobiliariaId(id);
        if (jaGarantido.isPresent()) {
            return new WriteResult(jaGarantido.get().getCliCodigo());
        }

        // A Pleno exige um `cliente` (logo um `pes_codigo`) para criar a imobiliaria.
        // Se a empresa ja tem pessoa por CNPJ, adota; senao cria uma pessoa FICTICIA
        // (regra: o cadastro de negocio da imobiliaria vive no Inspecta; a Pleno so
        // precisa de um pes_codigo valido). Ver specs/fases/fase-6-pleno.md Passo 1.
        Long pesCodigo = extractCodigo(getQuiet("/api/vistoria/pessoa/buscaPorCnpj?pes_cnpj=" + cnpj), "pes_codigo");
        DadosFalsos falsos = null;
        if (pesCodigo == null) {
            falsos = DadosFalsos.gerar();
            Map<String, String> f = new LinkedHashMap<>();
            f.put("pes_tipo", "F");
            f.put("pes_nome", falsos.nome());
            f.put("pes_cpf", falsos.cpf());
            f.put("pes_email", falsos.email());
            f.put("pes_telefone", digitos(falsos.telefone()));
            pesCodigo = exigeCodigo(postForm("/api/vistoria/pessoa/store", f), "pes_codigo", "pessoa/store (imobiliaria garantia)");
        }

        Map<String, String> cli = new LinkedHashMap<>();
        cli.put("pes_codigo", String.valueOf(pesCodigo));
        cli.put("cli_contestacao_data_limite", String.valueOf(cliContestacaoDias));
        Long cliCodigo = exigeCodigo(postForm("/api/vistoria/cliente/store", cli), "cli_codigo", "cliente/store");

        // Guarda "para garantia" (rastreabilidade + chave de idempotencia).
        PlenoPessoaGarantia g = new PlenoPessoaGarantia();
        g.setImobiliariaId(id);
        g.setPesCodigo(String.valueOf(pesCodigo));
        g.setCliCodigo(String.valueOf(cliCodigo));
        g.setNome(falsos != null ? falsos.nome() : im.getRazaoSocial());
        g.setCpf(falsos != null ? falsos.cpf() : cnpj);
        g.setEmail(falsos != null ? falsos.email()
                : (notBlank(im.getEmailFinanceiro()) ? im.getEmailFinanceiro() : "n/d"));
        g.setTelefone(falsos != null ? falsos.telefone()
                : (notBlank(im.getTelefone()) ? im.getTelefone() : "n/d"));
        pessoaGarantia.save(g);

        return new WriteResult(String.valueOf(cliCodigo));
    }


    private WriteResult espelharVistoriador(UUID id) {
        Vistoriador v = vistoriadores.findById(id)
                .orElseThrow(() -> new IllegalStateException("vistoriador " + id + " nao encontrado"));
        if (!v.isAtivo()) throw new IllegalStateException("vistoriador " + id + ": inativo, nao espelha");
        if (notBlank(v.getPlenoId())) return new WriteResult(v.getPlenoId());

        Usuario u = usuarios.findById(v.getUsuarioId())
                .orElseThrow(() -> new IllegalStateException("vistoriador " + id + ": usuario " + v.getUsuarioId() + " ausente"));
        String email = u.getEmail();
        String nome = notBlank(u.getNome()) ? u.getNome() : email;

        // Probe: usuario Pleno ja existe com esse email?
        Long usuCodigo = extractCodigo(
                getQuiet("/api/portalDoCliente/usuario?term=" + enc(email) + "&ativo=all"), "usu_codigo");

        if (usuCodigo == null) {
            // usuario/adicionar exige cli_codigo (4.3): usa o da pessoa-garantia da imobiliaria
            // primaria do vistoriador. Se ainda nao ha garantia, adia (DAG): vistoriador ->
            // imobiliaria primaria espelhada.
            String cliCodigo = cliCodigoDaImobiliariaPrimaria(u, id);

            Map<String, String> pes = new LinkedHashMap<>();
            pes.put("pes_tipo", "F");
            pes.put("pes_nome", nome);
            pes.put("pes_email", email);
            pes.put("pes_telefone", digitos(DadosFalsos.telefoneFalso())); // obrigatorio; usuario nao tem telefone
            Long pesCodigo = exigeCodigo(postForm("/api/vistoria/pessoa/store", pes), "pes_codigo", "pessoa/store (vistoriador)");

            Map<String, String> add = new LinkedHashMap<>();
            add.put("pes_codigo", String.valueOf(pesCodigo));
            add.put("pes_nome", nome);
            add.put("usu_email", email);
            add.put("usu_senha", senhaPrimeiroAcesso());
            add.put("cli_codigo", cliCodigo);
            add.put("gru_codigo", String.valueOf(gruCodigoVistoriador));
            if (notBlank(usuCodigoModelo)) add.put("usu_codigo_modelo", usuCodigoModelo);
            List<String> sistemas = sistemasVistoriador();
            for (int i = 0; i < sistemas.size(); i++) {
                add.put("sistemas[" + i + "]", sistemas.get(i));
            }
            usuCodigo = exigeCodigo(postForm("/api/vistoria/usuario/adicionar", add), "usu_codigo", "usuario/adicionar");

            // Onboarding (4.9, Q19-20): a Pleno usa a usu_senha que mandamos e NAO manda e-mail.
            // Avisa o vistoriador pra definir a senha no inspecta-api da Pleno. Mesma @Transactional (regra 3).
            // Se esta tx der rollback pos-usuario/adicionar, a re-entrega readota via probe e pula
            // este bloco — o e-mail de onboarding se perde (o vistoriador ainda pode usar
            // "esqueci a senha" no inspecta-api da Pleno). Aceito: LOW.
            outbox.publish("notify.requested.vistoriador-pleno-criado", Map.of(
                    "eventId", "pleno-vist-criado-" + v.getId(),
                    "canal", "email",
                    "tipoComunicacao", "operacional",
                    "templateId", NotifyTemplates.VISTORIADOR_PLENO_CRIADO,
                    "destinatario", email,
                    "variaveis", Map.of(
                            "nome", nome,
                            "portalUrl", baseUrl + "/api/portalDoCliente/usuario/recuperarSenha")));
        }

        v.setPlenoId(String.valueOf(usuCodigo));
        vistoriadores.save(v);
        return new WriteResult(v.getPlenoId());
    }

    /**
     * {@code cli_codigo} da pessoa-garantia da imobiliaria primaria do vistoriador (4.3). A primaria
     * e {@code usuarios.imobiliaria_id} (V25); se nula, a primeira de {@code usuario_imobiliarias}.
     * Sem imobiliaria primaria ou sem garantia espelhada: adia (reenfileira + lanca), igual as
     * outras dependencias do DAG.
     */
    private String cliCodigoDaImobiliariaPrimaria(Usuario u, UUID vistoriadorId) {
        UUID imobId = u.getImobiliariaId();
        if (imobId == null) {
            imobId = usuarioImobiliarias.findImobiliariaIdsByUsuarioId(u.getId()).stream()
                    .findFirst().orElse(null);
        }
        if (imobId == null)
            throw new IllegalStateException("vistoriador " + vistoriadorId + ": sem imobiliaria primaria — "
                    + "usuario/adicionar da Pleno exige cli_codigo");
        UUID imobFinal = imobId;
        return pessoaGarantia.findByImobiliariaId(imobFinal)
                .map(PlenoPessoaGarantia::getCliCodigo)
                .filter(PlenoWriteClientHttp::notBlank)
                .orElseGet(() -> {
                    integracaoSync.enfileirarIsolado("imobiliaria", imobFinal);
                    throw new IllegalStateException("dependencia imobiliaria/" + imobFinal
                            + " ainda nao espelhada (garantia do vistoriador " + vistoriadorId + ")");
                });
    }


    private WriteResult espelharImovel(UUID id) {
        Imovel im = imoveis.findById(id)
                .orElseThrow(() -> new IllegalStateException("imovel " + id + " nao encontrado"));
        if (im.getImobiliariaId() == null) throw new IllegalStateException("imovel " + id + ": sem imobiliaria");
        exigeDependencia("imobiliaria", im.getImobiliariaId());
        String cep = digitos(im.getCep());
        if (cep.length() != 8) throw new IllegalStateException("imovel " + id + ": CEP invalido");
        if (im.getEndereco() == null || im.getEndereco().isBlank())
            throw new IllegalStateException("imovel " + id + ": endereco vazio");

        // Re-adocao por chave natural (imo_codigo_externo == imoveis.id)
        Long jaExiste = buscarImovelPorExterno(id);
        if (jaExiste != null) return new WriteResult(String.valueOf(jaExiste));

        JsonNode addr = exigeData(getJson("/api/vistoria/address?cep=" + cep), "address?cep");
        String ufCodigo = texto(addr, "uf_codigo");
        String cidCodigo = texto(addr, "cid_codigo");
        String baiCodigo = texto(addr, "bai_codigo");
        if (ufCodigo == null || cidCodigo == null)
            throw new IllegalStateException("imovel " + id + ": CEP " + cep + " sem uf/cidade na Pleno");
        // bai_codigo ausente NAO estoura (4.5): a Pleno resolve o bairro via Correios e permite
        // preenchimento manual. So cai no erro se o proprio imovel/store recusar.

        Map<String, String> f = new LinkedHashMap<>();
        f.put("tip_imo_codigo", String.valueOf(tipImoCodigo(im.getTipo())));
        f.put("imo_cep", cep);
        f.put("uf_codigo", ufCodigo);
        f.put("cid_codigo", cidCodigo);
        if (baiCodigo != null) f.put("bai_codigo", baiCodigo);
        f.put("imo_endereco", im.getEndereco());
        String imoNumero = numeroDoEndereco(im.getEndereco()); // obrigatorio (4.4); "S/N" se nao der pra derivar
        if ("S/N".equals(imoNumero))
            log.warn("[pleno] imovel {}: imo_numero nao derivavel de '{}' — enviando S/N (ver divida pendencias.md)",
                    id, im.getEndereco());
        f.put("imo_numero", imoNumero);
        f.put("imo_codigo_externo", String.valueOf(id));

        // proprietarios[] (locadores) NÃO vão no imovel/store: no momento do espelho do
        // imóvel não há solicitação, e as partes agora vivem só em solicitacao_intervenientes.
        // A Pleno aceita o imóvel sem proprietário e o vínculo entra no solicitacao/store.

        Long imoCodigo = exigeCodigo(postForm("/api/vistoria/imovel/store", f), "imo_codigo", "imovel/store");
        return new WriteResult(String.valueOf(imoCodigo));
    }

    private Long buscarImovelPorExterno(UUID imovelId) {
        JsonNode data = getQuiet("/api/vistoria/imovel?term=" + imovelId);
        if (data == null) return null;
        for (JsonNode el : data.isArray() ? data : List.of(data)) {
            if (String.valueOf(imovelId).equals(texto(el, "imo_codigo_externo")))
                return el.path("imo_codigo").isNumber() ? el.path("imo_codigo").asLong() : null;
        }
        return null;
    }


    private WriteResult espelharSolicitacao(UUID id) {
        Solicitacao s = solicitacoes.findById(id)
                .orElseThrow(() -> new IllegalStateException("solicitacao " + id + " nao encontrada"));
        List<SolicitacaoServico> itens = servicos.findBySolicitacaoId(id);
        if (itens.isEmpty()) throw new IllegalStateException("solicitacao " + id + ": sem servicos");

        String imoCodigo = exigeDependencia("imovel", s.getImovelId());

        String cliCodigo = null;
        if (s.getImobiliariaId() != null) {
            cliCodigo = exigeDependencia("imobiliaria", s.getImobiliariaId());
        }

        // vistoriadores atribuidos devem ter pleno_id
        List<String> usuCodigos = new ArrayList<>();
        for (SolicitacaoServico it : itens) {
            if (it.getVistoriadorId() == null) continue;
            Vistoriador v = vistoriadores.findById(it.getVistoriadorId())
                    .orElseThrow(() -> new IllegalStateException("solicitacao " + id + ": vistoriador " + it.getVistoriadorId() + " ausente"));
            if (!notBlank(v.getPlenoId())) {
                integracaoSync.enfileirarIsolado("vistoriador", v.getId());
                throw new IllegalStateException("dependencia vistoriador/" + v.getId() + " ainda nao espelhada");
            }
            if (!usuCodigos.contains(v.getPlenoId())) usuCodigos.add(v.getPlenoId());
        }

        Imovel imovel = imoveis.findById(s.getImovelId())
                .orElseThrow(() -> new IllegalStateException("solicitacao " + id + ": imovel ausente"));

        Optional<LocalDateTime> agendamento = itens.stream()
                .map(SolicitacaoServico::getDataAgendada)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .map(inst -> LocalDateTime.ofInstant(inst, ZONA));

        Map<String, String> f = new LinkedHashMap<>();
        f.put("tip_vis_codigo", String.valueOf(tipVisCodigo(itens)));
        f.put("imo_codigo", imoCodigo);
        if (cliCodigo != null) f.put("cli_codigo", cliCodigo);
        f.put("sol_metragem", imovel.getMetragem() == null ? "0" : imovel.getMetragem().toPlainString());
        f.put("sol_mobilia", imovel.isMobiliado() ? "1" : "0");
        agendamento.ifPresent(dt -> f.put("sol_agendamento", dt.format(AGENDAMENTO)));
        if (notBlank(s.getChavesRetirada())) f.put("sol_local_chaves", s.getChavesRetirada());
        f.put("sol_identificacao", "INSPECTA-" + id);

        for (int i = 0; i < usuCodigos.size(); i++) {
            f.put("vistoriadores[" + i + "][usu_codigo]", usuCodigos.get(i));
        }

        List<SolicitacaoInterveniente> pessoas = intervenientes.findBySolicitacaoId(id);
        for (int i = 0; i < pessoas.size(); i++) {
            SolicitacaoInterveniente iv = pessoas.get(i);
            long pes = resolverPessoa(iv.getNome(), iv.getCpf(), iv.getEmail(), iv.getTelefone());
            boolean assina = !"testemunha".equals(iv.getPapelSlug());
            f.put("pessoas[" + i + "][pes_codigo]", String.valueOf(pes));
            f.put("pessoas[" + i + "][tip_fun_codigo]", String.valueOf(tipFunCodigo(iv.getPapelSlug())));
            // sol_aco_interveniente: 1 = acompanha a vistoria (flag do interveniente da solicitação).
            f.put("pessoas[" + i + "][sol_aco_interveniente]", iv.isAcompanhante() ? "1" : "0");
            f.put("pessoas[" + i + "][sol_aco_assina]", assina ? "1" : "0");
        }

        // solicitacao/store espera multipart/form-data (4.6), nao urlencoded.
        Long solCodigo = exigeCodigo(postMultipart("/api/vistoria/solicitacao/store", f), "sol_codigo", "solicitacao/store");
        return new WriteResult(String.valueOf(solCodigo));
    }


    /** Resolve o {@code external_id} de uma dependencia; adia (reenfileira + lanca) se ainda pendente. */
    private String exigeDependencia(String recursoTipo, UUID recursoId) {
        String externalId = integracaoSync.buscar(recursoTipo, recursoId)
                .map(IntegracaoSync::getExternalId)
                .filter(PlenoWriteClientHttp::notBlank)
                .orElse(null);
        if (externalId == null) {
            // REQUIRES_NEW: este metodo lanca logo abaixo e a transacao do espelhar() sera
            // desfeita — a linha da dependencia precisa commitar por fora pra entrar na fila.
            integracaoSync.enfileirarIsolado(recursoTipo, recursoId);
            throw new IllegalStateException("dependencia " + recursoTipo + "/" + recursoId + " ainda nao espelhada");
        }
        return externalId;
    }

    /**
     * {@code sistemas[]} do vistoriador na Pleno ({@code inspecta.pleno.depara.vistoriador-sistemas}).
     * Ignora entradas em branco (vírgula sobrando, env setada vazia) e cai no default {@code 6,3}
     * se sobrar lista vazia — nunca manda o vistoriador pra Pleno sem sistema.
     */
    private List<String> sistemasVistoriador() {
        List<String> efetivo = vistoriadorSistemas.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        return efetivo.isEmpty() ? List.of("6", "3") : efetivo;
    }

    private static final Map<String, Integer> TIP_FUN_FALLBACK =
            Map.of("locador", 5, "locatario", 4, "fiador", 3, "testemunha", 6);

    /** intervenientes.papel_slug -> tip_fun_codigo Pleno ({@code inspecta.pleno.depara.tip-fun-por-papel}). */
    private int tipFunCodigo(String papelSlug) {
        Integer fromDepara = pares(tipFunPorPapel).get(papelSlug);
        if (fromDepara != null) return fromDepara;
        return TIP_FUN_FALLBACK.getOrDefault(papelSlug, 4); // 4 = Locatario, default conservador
    }

    /**
     * tip_vis_codigo da solicitacao (4.1). V5: cada combinacao Servico (guarda-chuva) x Tipo de
     * Servico pode ter seu proprio {@code servico_tipos.pleno_tip_vis_codigo} — a Pleno identifica
     * o tipo de vistoria so por esse numero, e o mesmo tipo de servico ("Vistoria de entrada")
     * pode ter codigo diferente dependendo do Servico ao qual pertence. Por item, na ordem: se
     * algum vinculo {@code servico_tipos} do {@code tipoServicoId} tiver codigo configurado, usa
     * (primeiro encontrado; ambiguidade entre Servicos diferentes so loga warn — decisao de
     * negocio pendente, nao trava o fluxo). So se NENHUM item tiver codigo configurado, cai no
     * de-para legado por nome ({@link #tipVisCodigoPorNome}).
     */
    private int tipVisCodigo(List<SolicitacaoServico> itens) {
        for (SolicitacaoServico it : itens) {
            List<ServicoTipo> vinculos = servicoTipos.findByTipoServicoId(it.getTipoServicoId());
            List<Integer> codigos = vinculos.stream()
                    .map(ServicoTipo::getPlenoTipVisCodigo)
                    .filter(Objects::nonNull)
                    .toList();
            if (codigos.isEmpty()) continue;
            if (codigos.stream().distinct().count() > 1) {
                log.warn("[pleno] tip_vis_codigo: tipo_servico {} tem pleno_tip_vis_codigo ambiguo entre "
                        + "servicos ({}) — usando o primeiro encontrado ({}). Decisao de negocio pendente "
                        + "(ver pendencias.md).", it.getTipoServicoId(), codigos, codigos.get(0));
            }
            return codigos.get(0);
        }
        return tipVisCodigoPorNome(itens);
    }

    /**
     * tip_vis_codigo legado (4.1, T6.16): olha o nome do {@code tipos_servico} de cada item e casa
     * com uma substring de {@code inspecta.pleno.depara.tip-vis-por-nome} (entrada->2, saida->6).
     * Sem casar: default {@code tipVisCodigo} (=2). O codigo de CONFERENCIA nao esta documentado —
     * confirmar via {@code GET /api/vistoria/tipoVistoria} no tenant alvo.
     */
    private int tipVisCodigoPorNome(List<SolicitacaoServico> itens) {
        List<String> nomes = new ArrayList<>();
        for (SolicitacaoServico it : itens) {
            String nome = tiposServico.findById(it.getTipoServicoId())
                    .map(TipoServico::getNome).orElse(null);
            if (nome == null) {
                log.warn("[pleno] tipo_servico {} nao encontrado ao resolver tip_vis_codigo", it.getTipoServicoId());
                continue;
            }
            nomes.add(nome);
            Integer c = casaPorSubstring(tipVisPorNome, normalizar(nome));
            if (c != null) return c;
        }
        log.warn("[pleno] tip_vis_codigo: nenhum tipo_servico {} casou com tip-vis-por-nome — usando default {}",
                nomes, tipVisCodigo);
        return tipVisCodigo;
    }

    /** imoveis.tipo -> tip_imo_codigo ({@code inspecta.pleno.depara.tip-imo-por-tipo}); default por tenant. */
    private int tipImoCodigo(String tipo) {
        Integer c = casaPorSubstring(tipImoPorTipo, normalizar(tipo));
        if (c != null) return c;
        log.debug("[pleno] tip_imo_codigo: imoveis.tipo '{}' sem match em tip-imo-por-tipo — usando default {}",
                tipo, tipImoCodigo);
        return tipImoCodigo;
    }

    /** Primeiro valor de {@code pares} cuja chave normalizada e substring de {@code alvo}; null se nenhum. */
    private static Integer casaPorSubstring(List<String> pares, String alvo) {
        for (Map.Entry<String, Integer> e : pares(pares).entrySet()) {
            if (!alvo.isEmpty() && alvo.contains(normalizar(e.getKey()))) return e.getValue();
        }
        return null;
    }

    /** Parseia uma lista {@code ["chave:valor", ...]} de de-para em {@code Map<String,Integer>}. */
    private static Map<String, Integer> pares(List<String> lista) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String par : lista) {
            int sep = par.lastIndexOf(':');
            if (sep > 0) {
                try {
                    m.put(par.substring(0, sep).trim(), Integer.parseInt(par.substring(sep + 1).trim()));
                    continue;
                } catch (NumberFormatException ignored) {
                    // cai no log abaixo
                }
            }
            // de-para mal-formado: ignora a entrada (o default cobre) mas deixa rastro —
            // um override de operador fat-fingered nao pode sumir sem aviso.
            log.warn("[pleno] entrada de de-para ignorada (esperado 'chave:numero'): '{}'", par);
        }
        return m;
    }

    /**
     * {@code imo_numero} obrigatorio (4.4): extrai o numero do fim de {@code imoveis.endereco}
     * (o Inspecta so tem endereco livre). Sem numero legivel -> {@code "S/N"}. DIVIDA: separar
     * numero/complemento do endereco do imovel (migration + form inspecta-web) — ver pendencias.md.
     * O caller ({@code espelharImovel}) loga quando cai no fallback, pra dar pra auditar.
     */
    static String numeroDoEndereco(String endereco) {
        if (endereco == null) return "S/N";
        Matcher m = NUMERO_ENDERECO.matcher(endereco.trim());
        return m.find() ? m.group(1) : "S/N";
    }

    private static String normalizar(String s) {
        return java.text.Normalizer.normalize(s == null ? "" : s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase().trim();
    }

    /** Achata CR/LF em espaco — proteje o corpo multipart de quebra por texto livre do inspecta-api. */
    private static String umaLinha(String s) {
        return s == null ? "" : s.replaceAll("[\\r\\n]+", " ");
    }

    private long resolverPessoa(String nome, String cpf, String email, String telefone) {
        String cpfDigitos = cpf == null ? "" : digitos(cpf);
        if (cpfDigitos.length() == 11) {
            Long achado = extractCodigo(getQuiet("/api/vistoria/pessoa/buscaPorCpf?pes_cpf=" + cpfDigitos), "pes_codigo");
            if (achado != null) return achado;
        }
        Map<String, String> f = new LinkedHashMap<>();
        f.put("pes_tipo", "F");
        f.put("pes_nome", notBlank(nome) ? nome : "Interveniente");
        if (notBlank(email)) f.put("pes_email", email);
        // pes_telefone e obrigatorio em todo pessoa/store (4.2) — placeholder quando ausente.
        f.put("pes_telefone", digitos(notBlank(telefone) ? telefone : DadosFalsos.telefoneFalso()));
        if (cpfDigitos.length() == 11) f.put("pes_cpf", cpfDigitos);
        return exigeCodigo(postForm("/api/vistoria/pessoa/store", f), "pes_codigo", "pessoa/store (interveniente)");
    }

    /** POST x-www-form-urlencoded; devolve o node {@code data}, lancando se {@code success != true}. */
    private JsonNode postForm(String path, Map<String, String> form) {
        HttpGatewayResponse res;
        try {
            res = httpGateway.execute(HttpGatewayRequest.post(
                    URI.create(baseUrl + path),
                    Map.of("Authorization", "Bearer " + token(),
                            "Content-Type", "application/x-www-form-urlencoded",
                            "Accept", "application/json"),
                    urlencode(form)));
        } catch (Exception e) {
            throw new IllegalStateException("Pleno " + path + " falhou: " + e, e);
        }
        return envelope(path, res);
    }

    /**
     * POST {@code multipart/form-data} sem arquivo — so campos de texto, inclusive os arrays
     * aninhados {@code chave[i][sub]}. Usado so no {@code solicitacao/store} (4.6), que espera
     * multipart. {@code RealHttpGateway} ja transporta um corpo arbitrario + Content-Type, entao
     * nao precisa de mudanca no inspecta-core. Precisa de teste contra o endpoint real.
     */
    private JsonNode postMultipart(String path, Map<String, String> form) {
        String boundary = "InspectaPlenoBoundary" + UUID.randomUUID().toString().replace("-", "");
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            // Campos vem de texto livre do inspecta-api (endereco, sol_local_chaves, nome de
            // interveniente): um CR/LF corromperia o stream multipart. Troca por espaco.
            String nome = umaLinha(e.getKey());
            String valor = umaLinha(e.getValue() == null ? "" : e.getValue());
            sb.append("--").append(boundary).append("\r\n")
              .append("Content-Disposition: form-data; name=\"").append(nome).append("\"\r\n\r\n")
              .append(valor).append("\r\n");
        }
        sb.append("--").append(boundary).append("--\r\n");
        HttpGatewayResponse res;
        try {
            res = httpGateway.execute(HttpGatewayRequest.post(
                    URI.create(baseUrl + path),
                    Map.of("Authorization", "Bearer " + token(),
                            "Content-Type", "multipart/form-data; boundary=" + boundary,
                            "Accept", "application/json"),
                    sb.toString()));
        } catch (Exception e) {
            throw new IllegalStateException("Pleno " + path + " falhou: " + e, e);
        }
        return envelope(path, res);
    }

    private JsonNode getJson(String path) {
        HttpGatewayResponse res;
        try {
            res = httpGateway.execute(HttpGatewayRequest.get(
                    URI.create(baseUrl + path),
                    Map.of("Authorization", "Bearer " + token(), "Accept", "application/json")));
        } catch (Exception e) {
            throw new IllegalStateException("Pleno " + path + " falhou: " + e, e);
        }
        return envelope(path, res);
    }

    /** GET que devolve o node {@code data} ou {@code null} — para probes / re-adocao onde a ausencia e esperada. */
    private JsonNode getQuiet(String path) {
        try {
            return getJson(path);
        } catch (RuntimeException e) {
            log.debug("[pleno] probe {} sem resultado: {}", path, e.getMessage());
            return null;
        }
    }

    private JsonNode envelope(String path, HttpGatewayResponse res) {
        JsonNode body;
        try {
            body = mapper.readTree(res.body() == null || res.body().isBlank() ? "{}" : res.body());
        } catch (Exception e) {
            throw new IllegalStateException("Pleno " + path + ": corpo ilegivel (HTTP " + res.status() + ")", e);
        }
        if (!body.path("success").asBoolean(false)) {
            String msg = body.path("error").path("message").asText(
                    body.path("message").asText("HTTP " + res.status()));
            throw new IllegalStateException("Pleno " + path + " recusou: " + msg);
        }
        return body.path("data");
    }

    private static JsonNode exigeData(JsonNode data, String origem) {
        if (data == null || data.isMissingNode() || data.isNull())
            throw new IllegalStateException("Pleno " + origem + ": resposta sem 'data'");
        return data;
    }

    private static Long exigeCodigo(JsonNode data, String field, String origem) {
        Long c = extractCodigo(data, field);
        if (c == null) throw new IllegalStateException("Pleno " + origem + ": resposta sem '" + field + "'");
        return c;
    }

    /** Procura {@code field} num node que pode ser objeto, array ou vir aninhado em wrappers comuns. */
    private static Long extractCodigo(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.isArray()) {
            for (JsonNode el : node) {
                Long c = extractCodigo(el, field);
                if (c != null) return c;
            }
            return null;
        }
        JsonNode direto = node.get(field);
        if (direto != null && direto.canConvertToLong()) return direto.asLong();
        if (direto != null && direto.isTextual() && direto.asText().matches("\\d+")) return Long.parseLong(direto.asText());
        for (String w : new String[]{"pessoa", "cliente", "usuario", "imovel", "solicitacao", "data", "result"}) {
            if (node.has(w)) {
                Long c = extractCodigo(node.get(w), field);
                if (c != null) return c;
            }
        }
        return null;
    }

    private static String texto(JsonNode node, String field) {
        JsonNode n = node == null ? null : node.get(field);
        if (n == null || n.isNull() || n.isMissingNode()) return null;
        String s = n.asText();
        return s.isBlank() ? null : s;
    }

    private static String urlencode(Map<String, String> form) {
        return form.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue() == null ? "" : e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String digitos(String s) {
        return s == null ? "" : s.replaceAll("\\D", "");
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** Senha aleatoria de primeiro acesso — regra 10: nunca trafega nosso hash. */
    private static String senhaPrimeiroAcesso() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** Únicos recursos com {@code GET /api/vistoria/{tipo}/{id}/show} confirmado na Pleno (4.7). */
    private static final java.util.Set<String> TIPOS_COM_SHOW = java.util.Set.of("solicitacao", "vistoria");

    @Override
    public StatusResult syncStatus(String recursoTipo, UUID recursoId) {
        // Fora de solicitacao/vistoria a Pleno nao tem /show — o estado local (external_id) e a
        // unica verdade disponivel. Nao detecta drift remoto (registro apagado/rejeitado do lado
        // da Pleno); e o melhor possivel sem endpoint. Tipo desconhecido tambem cai aqui e vira
        // "erro" (sem linha local), o que e o comportamento seguro.
        if (!TIPOS_COM_SHOW.contains(recursoTipo)) {
            boolean temExternalId = integracaoSync.buscar(recursoTipo, recursoId)
                    .map(IntegracaoSync::getExternalId)
                    .filter(PlenoWriteClientHttp::notBlank)
                    .isPresent();
            log.debug("[pleno] syncStatus {}/{} sem /show remoto — estado local: {}",
                    recursoTipo, recursoId, temExternalId ? "sincronizado" : "sem external_id");
            return temExternalId
                    ? new StatusResult("sincronizado", null)
                    : new StatusResult("erro", "sem external_id local para " + recursoTipo + "/" + recursoId);
        }
        try {
            HttpGatewayResponse res = httpGateway.execute(HttpGatewayRequest.get(
                    URI.create(baseUrl + "/api/vistoria/" + recursoTipo + "/" + recursoId + "/show"),
                    Map.of("Authorization", "Bearer " + token())));
            if (!res.sucesso()) return new StatusResult("erro", "HTTP " + res.status());
            JsonNode body = mapper.readTree(res.body().isBlank() ? "{}" : res.body());
            return body.path("success").asBoolean(false)
                    ? new StatusResult("sincronizado", null)
                    : new StatusResult("erro", body.path("error").path("message").asText("erro desconhecido"));
        } catch (Exception e) {
            return new StatusResult("erro", e.toString());
        }
    }
}
