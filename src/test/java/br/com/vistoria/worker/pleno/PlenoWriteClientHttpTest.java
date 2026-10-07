package br.com.vistoria.worker.pleno;

import br.com.vistoria.core.gateway.HttpGateway;
import br.com.vistoria.core.gateway.HttpGatewayRequest;
import br.com.vistoria.core.gateway.HttpGatewayResponse;
import br.com.vistoria.core.modules.cadastro.CadastroRepositories.ImovelRepository;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoRepositories.SolicitacaoIntervenienteRepository;
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
import br.com.vistoria.core.modules.integracao.IntegracaoSyncService;
import br.com.vistoria.core.modules.integracao.PlenoAuthService;
import br.com.vistoria.core.modules.integracao.PlenoPessoaGarantia;
import br.com.vistoria.core.modules.integracao.PlenoTokenStore;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoRepositories.ServicoRepository;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoRepositories.SolicitacaoRepository;
import br.com.vistoria.core.modules.solicitacao.Solicitacao;
import br.com.vistoria.core.modules.solicitacao.SolicitacaoServico;
import br.com.vistoria.core.modules.usuario.Usuario;
import br.com.vistoria.core.modules.usuario.UsuarioImobiliariaRepository;
import br.com.vistoria.core.modules.usuario.UsuarioRepository;
import br.com.vistoria.core.shared.outbox.OutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PlenoWriteClientHttp")
class PlenoWriteClientHttpTest {

    @Mock private PlenoTokenStore tokenStore;
    @Mock private PlenoAuthService authService;
    @Mock private HttpGateway httpGateway;
    @Mock private IntegracaoSyncService integracaoSync;
    @Mock private ImobiliariaRepository imobiliarias;
    @Mock private ImovelRepository imoveis;
    @Mock private SolicitacaoIntervenienteRepository intervenientes;
    @Mock private VistoriadorRepository vistoriadores;
    @Mock private UsuarioRepository usuarios;
    @Mock private SolicitacaoRepository solicitacoes;
    @Mock private ServicoRepository servicos;
    @Mock private TipoServicoRepository tiposServico;
    @Mock private ServicoTipoRepository servicoTipos;
    @Mock private br.com.vistoria.core.modules.integracao.PlenoPessoaGarantiaRepository pessoaGarantia;
    @Mock private UsuarioImobiliariaRepository usuarioImobiliarias;
    @Mock private OutboxService outbox;

    private PlenoWriteClientHttp client;

    @BeforeEach
    void setUp() {
        client = new PlenoWriteClientHttp(tokenStore, authService, httpGateway, integracaoSync,
                imobiliarias, imoveis, intervenientes, vistoriadores, usuarios, solicitacoes, servicos,
                tiposServico, servicoTipos, pessoaGarantia, usuarioImobiliarias, outbox);
        ReflectionTestUtils.setField(client, "baseUrl", "https://api.pleno.com.br");
        // Os campos de inspecta.pleno.depara.* têm inicializador de campo com os defaults do
        // InspectaSystem v1 (36/1/15/5, TIP_FUN 5/4/3/6, sistemas 6,3) — sem Spring pra processar
        // @Value, é o inicializador que vale, então não é preciso setá-los aqui.
        when(tokenStore.get()).thenReturn("valid-token");
    }

    private static HttpGatewayResponse ok(String body) {
        return new HttpGatewayResponse(200, null, body);
    }


    @Test
    @DisplayName("espelhar imobiliaria: cria pessoa J + cliente e devolve cli_codigo")
    void espelhar_imobiliaria_criaPessoaECliente() throws Exception {
        UUID imobiliariaId = UUID.randomUUID();
        Imobiliaria im = new Imobiliaria();
        im.setId(imobiliariaId);
        im.setRazaoSocial("Imob Teste LTDA");
        im.setCnpj("12.345.678/0001-99");
        im.setEmailFinanceiro("fin@imob.com");
        im.setAtiva(true);
        when(imobiliarias.findById(imobiliariaId)).thenReturn(Optional.of(im));

        when(httpGateway.execute(any(HttpGatewayRequest.class))).thenAnswer(inv -> {
            HttpGatewayRequest req = inv.getArgument(0);
            String u = req.uri().toString();
            if (u.contains("buscaPorCnpj")) return ok("{\"success\":false,\"error\":{\"message\":\"nao encontrado\"}}");
            if (u.endsWith("/pessoa/store")) return ok("{\"success\":true,\"data\":{\"pes_codigo\":50}}");
            if (u.endsWith("/cliente/store")) return ok("{\"success\":true,\"data\":{\"cli_codigo\":77}}");
            return ok("{\"success\":false}");
        });

        PlenoWriteClient.WriteResult r = client.espelhar("imobiliaria", imobiliariaId);

        assertThat(r.externalId()).isEqualTo("77");
    }

    @Test
    @DisplayName("espelhar imovel sem imobiliaria espelhada: reenfileira dependencia e lanca")
    void espelhar_imovel_dependenciaPendente_adiaEThrow() {
        UUID imovelId = UUID.randomUUID();
        UUID imobiliariaId = UUID.randomUUID();
        Imovel im = new Imovel();
        im.setId(imovelId);
        im.setImobiliariaId(imobiliariaId);
        im.setCep("99010040");
        im.setEndereco("Av Italia 388");
        when(imoveis.findById(imovelId)).thenReturn(Optional.of(im));
        when(integracaoSync.buscar("imobiliaria", imobiliariaId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> client.espelhar("imovel", imovelId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ainda nao espelhada");

        verify(integracaoSync).enfileirarIsolado("imobiliaria", imobiliariaId);
    }

    @Test
    @DisplayName("espelhar imovel com imobiliaria espelhada: resolve endereco e cria imovel")
    void espelhar_imovel_criaComEndereco() throws Exception {
        UUID imovelId = UUID.randomUUID();
        UUID imobiliariaId = UUID.randomUUID();
        Imovel im = new Imovel();
        im.setId(imovelId);
        im.setImobiliariaId(imobiliariaId);
        im.setCep("99010-040");
        im.setEndereco("Av Italia 388");
        when(imoveis.findById(imovelId)).thenReturn(Optional.of(im));

        IntegracaoSync dep = new IntegracaoSync();
        dep.setExternalId("77");
        when(integracaoSync.buscar("imobiliaria", imobiliariaId)).thenReturn(Optional.of(dep));

        when(httpGateway.execute(any(HttpGatewayRequest.class))).thenAnswer(inv -> {
            String u = ((HttpGatewayRequest) inv.getArgument(0)).uri().toString();
            if (u.contains("/imovel?term=")) return ok("{\"success\":true,\"data\":[]}");
            if (u.contains("/address?cep=")) return ok("{\"success\":true,\"data\":{\"uf_codigo\":23,\"cid_codigo\":7566,\"bai_codigo\":58352}}");
            if (u.endsWith("/imovel/store")) return ok("{\"success\":true,\"data\":{\"imo_codigo\":123}}");
            return ok("{\"success\":false}");
        });

        PlenoWriteClient.WriteResult r = client.espelhar("imovel", imovelId);

        assertThat(r.externalId()).isEqualTo("123");
    }

    @Test
    @DisplayName("espelhar recurso_tipo desconhecido lanca")
    void espelhar_tipoInvalido_throws() {
        assertThatThrownBy(() -> client.espelhar("laudo", UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nao espelhavel");
    }

    @Test
    @DisplayName("espelhar valida disponibilidade do token")
    void espelhar_semToken_throws() {
        when(tokenStore.get()).thenReturn(null);
        assertThatThrownBy(() -> client.espelhar("imobiliaria", UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Token Pleno");
    }


    @Test
    @DisplayName("tipFunCodigo mapeia cada papel_slug do interveniente para o codigo Pleno (defaults v1)")
    void tipFunCodigo_mapeiaTodosOsPapeis() {
        assertThat((int) ReflectionTestUtils.invokeMethod(client, "tipFunCodigo", "locador")).isEqualTo(5);
        assertThat((int) ReflectionTestUtils.invokeMethod(client, "tipFunCodigo", "locatario")).isEqualTo(4);
        assertThat((int) ReflectionTestUtils.invokeMethod(client, "tipFunCodigo", "fiador")).isEqualTo(3);
        // testemunha não é papel do catálogo Inspecta, mas o de-para/fallback ainda a resolve.
        assertThat((int) ReflectionTestUtils.invokeMethod(client, "tipFunCodigo", "testemunha")).isEqualTo(6);
    }

    @Test
    @DisplayName("sistemasVistoriador usa o default 6,3 e ignora entradas em branco / lista vazia")
    void sistemasVistoriador_normaliza() {
        assertThat((List<String>) ReflectionTestUtils.invokeMethod(client, "sistemasVistoriador"))
                .containsExactly("6", "3");

        ReflectionTestUtils.setField(client, "vistoriadorSistemas", List.of("6", " ", "3", ""));
        assertThat((List<String>) ReflectionTestUtils.invokeMethod(client, "sistemasVistoriador"))
                .containsExactly("6", "3");

        ReflectionTestUtils.setField(client, "vistoriadorSistemas", List.of("", "  "));
        assertThat((List<String>) ReflectionTestUtils.invokeMethod(client, "sistemasVistoriador"))
                .containsExactly("6", "3");

        ReflectionTestUtils.setField(client, "vistoriadorSistemas", List.of(" 9 ", "10"));
        assertThat((List<String>) ReflectionTestUtils.invokeMethod(client, "sistemasVistoriador"))
                .containsExactly("9", "10");
    }

    @Test
    @DisplayName("numeroDoEndereco extrai o numero do fim do endereco; S/N quando nao ha")
    void numeroDoEndereco_deriva() {
        assertThat(PlenoWriteClientHttp.numeroDoEndereco("Av Italia 388")).isEqualTo("388");
        assertThat(PlenoWriteClientHttp.numeroDoEndereco("Rua das Flores, 12B")).isEqualTo("12B");
        assertThat(PlenoWriteClientHttp.numeroDoEndereco("Rua sem numero")).isEqualTo("S/N");
        assertThat(PlenoWriteClientHttp.numeroDoEndereco(null)).isEqualTo("S/N");
    }


    /** url (sem query) -> corpo da ultima request; + content-type por url. */
    private Map<String, String> capturarRequests() throws Exception {
        Map<String, String> corpos = new HashMap<>();
        when(httpGateway.execute(any(HttpGatewayRequest.class))).thenAnswer(inv -> {
            HttpGatewayRequest req = inv.getArgument(0);
            String u = req.uri().toString();
            corpos.put(u, req.body() == null ? "" : req.body());
            corpos.put("ct:" + u.split("\\?")[0], req.headers().getOrDefault("Content-Type", ""));
            if (u.contains("buscaPor") || u.contains("?term=")) return ok("{\"success\":true,\"data\":[]}");
            if (u.contains("/address?cep=")) return ok("{\"success\":true,\"data\":{\"uf_codigo\":23,\"cid_codigo\":7566,\"bai_codigo\":58352}}");
            if (u.contains("/portalDoCliente/usuario?term=")) return ok("{\"success\":true,\"data\":[]}");
            if (u.endsWith("/pessoa/store")) return ok("{\"success\":true,\"data\":{\"pes_codigo\":50}}");
            if (u.endsWith("/cliente/store")) return ok("{\"success\":true,\"data\":{\"cli_codigo\":77}}");
            if (u.endsWith("/usuario/adicionar")) return ok("{\"success\":true,\"data\":{\"usu_codigo\":900}}");
            if (u.endsWith("/imovel/store")) return ok("{\"success\":true,\"data\":{\"imo_codigo\":123}}");
            if (u.endsWith("/solicitacao/store")) return ok("{\"success\":true,\"data\":{\"sol_codigo\":555}}");
            return ok("{\"success\":false}");
        });
        return corpos;
    }

    @Test
    @DisplayName("4.2/4.4/4.5: imovel/store manda imo_numero derivado, sem bai_codigo se ausente, e sem proprietarios[] (partes vivem na solicitacao)")
    void espelhar_imovel_numero_bairro_semProprietarios() throws Exception {
        UUID imovelId = UUID.randomUUID();
        UUID imobiliariaId = UUID.randomUUID();
        Imovel im = new Imovel();
        im.setId(imovelId); im.setImobiliariaId(imobiliariaId); im.setCep("99010-040"); im.setEndereco("Av Italia 388");
        when(imoveis.findById(imovelId)).thenReturn(Optional.of(im));
        IntegracaoSync dep = new IntegracaoSync(); dep.setExternalId("77");
        when(integracaoSync.buscar("imobiliaria", imobiliariaId)).thenReturn(Optional.of(dep));

        Map<String, String> corpos = capturarRequests();
        // address sem bai_codigo
        when(httpGateway.execute(any(HttpGatewayRequest.class))).thenAnswer(inv -> {
            HttpGatewayRequest req = inv.getArgument(0);
            String u = req.uri().toString();
            corpos.put(u, req.body() == null ? "" : req.body());
            if (u.contains("/imovel?term=")) return ok("{\"success\":true,\"data\":[]}");
            if (u.contains("/address?cep=")) return ok("{\"success\":true,\"data\":{\"uf_codigo\":23,\"cid_codigo\":7566}}");
            if (u.endsWith("/imovel/store")) return ok("{\"success\":true,\"data\":{\"imo_codigo\":123}}");
            return ok("{\"success\":false}");
        });

        PlenoWriteClient.WriteResult r = client.espelhar("imovel", imovelId);
        assertThat(r.externalId()).isEqualTo("123");

        String imovelStore = corpos.get("https://api.pleno.com.br/api/vistoria/imovel/store");
        assertThat(imovelStore).contains("imo_numero=388");
        assertThat(imovelStore).doesNotContain("bai_codigo");
        assertThat(imovelStore).doesNotContain("proprietarios[");
    }

    @Test
    @DisplayName("4.3: usuario/adicionar do vistoriador leva cli_codigo da garantia da imobiliaria primaria")
    void espelhar_vistoriador_usuarioAdicionar_temCliCodigo() throws Exception {
        UUID vistoriadorId = UUID.randomUUID();
        UUID usuarioId = UUID.randomUUID();
        UUID imobiliariaId = UUID.randomUUID();
        Vistoriador v = new Vistoriador(); v.setId(vistoriadorId); v.setUsuarioId(usuarioId); v.setAtivo(true);
        when(vistoriadores.findById(vistoriadorId)).thenReturn(Optional.of(v));
        Usuario u = new Usuario(); u.setId(usuarioId); u.setEmail("vist@imob.com"); u.setNome("Vitor"); u.setImobiliariaId(imobiliariaId);
        when(usuarios.findById(usuarioId)).thenReturn(Optional.of(u));
        PlenoPessoaGarantia g = new PlenoPessoaGarantia(); g.setImobiliariaId(imobiliariaId); g.setCliCodigo("77");
        when(pessoaGarantia.findByImobiliariaId(imobiliariaId)).thenReturn(Optional.of(g));

        Map<String, String> corpos = capturarRequests();
        PlenoWriteClient.WriteResult r = client.espelhar("vistoriador", vistoriadorId);

        assertThat(r.externalId()).isEqualTo("900");
        String add = corpos.get("https://api.pleno.com.br/api/vistoria/usuario/adicionar");
        assertThat(add).contains("cli_codigo=77");
        // pes_telefone (obrigatorio, 4.2) vai no pessoa/store, nao no usuario/adicionar
        assertThat(corpos.get("https://api.pleno.com.br/api/vistoria/pessoa/store")).contains("pes_telefone=");
        verify(outbox).publish(org.mockito.ArgumentMatchers.eq("notify.requested.vistoriador-pleno-criado"),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("4.3: sem imobiliaria-garantia espelhada, o vistoriador adia (reenfileira imobiliaria)")
    void espelhar_vistoriador_semGarantia_adia() throws Exception {
        UUID vistoriadorId = UUID.randomUUID();
        UUID usuarioId = UUID.randomUUID();
        UUID imobiliariaId = UUID.randomUUID();
        Vistoriador v = new Vistoriador(); v.setId(vistoriadorId); v.setUsuarioId(usuarioId); v.setAtivo(true);
        when(vistoriadores.findById(vistoriadorId)).thenReturn(Optional.of(v));
        Usuario u = new Usuario(); u.setId(usuarioId); u.setEmail("vist@imob.com"); u.setImobiliariaId(imobiliariaId);
        when(usuarios.findById(usuarioId)).thenReturn(Optional.of(u));
        when(pessoaGarantia.findByImobiliariaId(imobiliariaId)).thenReturn(Optional.empty());
        when(httpGateway.execute(any(HttpGatewayRequest.class)))
                .thenReturn(ok("{\"success\":true,\"data\":[]}"));

        assertThatThrownBy(() -> client.espelhar("vistoriador", vistoriadorId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("imobiliaria/" + imobiliariaId);
        verify(integracaoSync).enfileirarIsolado("imobiliaria", imobiliariaId);
    }

    @Test
    @DisplayName("4.1/4.6: solicitacao/store vai como multipart e o tip_vis_codigo vem do nome do tipo de servico")
    void espelhar_solicitacao_multipart_tipVisPorNome() throws Exception {
        UUID solicitacaoId = UUID.randomUUID();
        UUID imovelId = UUID.randomUUID();
        UUID tipoServicoId = UUID.randomUUID();
        Solicitacao s = new Solicitacao(); s.setId(solicitacaoId); s.setImovelId(imovelId);
        when(solicitacoes.findById(solicitacaoId)).thenReturn(Optional.of(s));
        SolicitacaoServico it = new SolicitacaoServico();
        it.setSolicitacaoId(solicitacaoId); it.setTipoServicoId(tipoServicoId); it.setDataAgendada(Instant.parse("2099-01-02T13:00:00Z"));
        when(servicos.findBySolicitacaoId(solicitacaoId)).thenReturn(List.of(it));
        IntegracaoSync imovelDep = new IntegracaoSync(); imovelDep.setExternalId("123");
        when(integracaoSync.buscar("imovel", imovelId)).thenReturn(Optional.of(imovelDep));
        Imovel im = new Imovel(); im.setId(imovelId);
        im.setMetragem(new java.math.BigDecimal("80")); im.setMobiliado(false);
        when(imoveis.findById(imovelId)).thenReturn(Optional.of(im));
        when(intervenientes.findBySolicitacaoId(solicitacaoId)).thenReturn(List.of());
        TipoServico tipo = new TipoServico(); tipo.setNome("Vistoria de Saída");
        when(tiposServico.findById(tipoServicoId)).thenReturn(Optional.of(tipo));

        Map<String, String> corpos = capturarRequests();
        PlenoWriteClient.WriteResult r = client.espelhar("solicitacao", solicitacaoId);

        assertThat(r.externalId()).isEqualTo("555");
        assertThat(corpos.get("ct:https://api.pleno.com.br/api/vistoria/solicitacao/store"))
                .startsWith("multipart/form-data; boundary=");
        String body = corpos.get("https://api.pleno.com.br/api/vistoria/solicitacao/store");
        assertThat(body).contains("name=\"tip_vis_codigo\"\r\n\r\n6\r\n"); // "saida" -> 6
    }


    @Test
    @DisplayName("V5: pleno_tip_vis_codigo configurado em servico_tipos vence o de-para por nome")
    void tipVisCodigo_configurado_usaCodigoDoServicoTipo() {
        UUID tipoServicoId = UUID.randomUUID();
        SolicitacaoServico it = new SolicitacaoServico();
        it.setTipoServicoId(tipoServicoId);
        ServicoTipo vinculo = new ServicoTipo();
        vinculo.setServicoId(UUID.randomUUID());
        vinculo.setTipoServicoId(tipoServicoId);
        vinculo.setPlenoTipVisCodigo(99);
        when(servicoTipos.findByTipoServicoId(tipoServicoId)).thenReturn(List.of(vinculo));

        int codigo = ReflectionTestUtils.invokeMethod(client, "tipVisCodigo", List.of(it));

        assertThat(codigo).isEqualTo(99);
        // ignora o de-para por nome: nao precisa nem resolver o nome do tipo de servico.
        verify(tiposServico, org.mockito.Mockito.never()).findById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("V5: sem pleno_tip_vis_codigo configurado, cai no de-para legado por nome (comportamento inalterado)")
    void tipVisCodigo_naoConfigurado_usaDeParaPorNomeLegado() {
        UUID tipoServicoId = UUID.randomUUID();
        SolicitacaoServico it = new SolicitacaoServico();
        it.setTipoServicoId(tipoServicoId);
        when(servicoTipos.findByTipoServicoId(tipoServicoId)).thenReturn(List.of());
        TipoServico tipo = new TipoServico();
        tipo.setNome("Vistoria de Saída");
        when(tiposServico.findById(tipoServicoId)).thenReturn(Optional.of(tipo));

        int codigo = ReflectionTestUtils.invokeMethod(client, "tipVisCodigo", List.of(it));

        assertThat(codigo).isEqualTo(6); // "saida" -> 6, comportamento legado inalterado
    }

    @Test
    @DisplayName("V5: tipo_servico com codigos ambiguos entre servicos usa o primeiro encontrado, loga warn e nao lanca")
    void tipVisCodigo_ambiguo_usaPrimeiroEncontradoELogaWarn() {
        UUID tipoServicoId = UUID.randomUUID();
        SolicitacaoServico it = new SolicitacaoServico();
        it.setTipoServicoId(tipoServicoId);
        ServicoTipo v1 = new ServicoTipo();
        v1.setServicoId(UUID.randomUUID());
        v1.setTipoServicoId(tipoServicoId);
        v1.setPlenoTipVisCodigo(10);
        ServicoTipo v2 = new ServicoTipo();
        v2.setServicoId(UUID.randomUUID());
        v2.setTipoServicoId(tipoServicoId);
        v2.setPlenoTipVisCodigo(20);
        when(servicoTipos.findByTipoServicoId(tipoServicoId)).thenReturn(List.of(v1, v2));

        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(PlenoWriteClientHttp.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            int codigo = ReflectionTestUtils.invokeMethod(client, "tipVisCodigo", List.of(it));

            assertThat(codigo).isEqualTo(10); // primeiro encontrado
            assertThat(appender.list)
                    .anyMatch(ev -> ev.getLevel() == ch.qos.logback.classic.Level.WARN
                            && ev.getFormattedMessage().contains("ambiguo"));
        } finally {
            logger.detachAppender(appender);
        }
    }


    @Test
    @DisplayName("syncStatus returns 'sincronizado' on success")
    void syncStatus_successResponse_returnsSincronizado() throws Exception {
        when(httpGateway.execute(any(HttpGatewayRequest.class)))
                .thenReturn(new HttpGatewayResponse(200, null, "{\"success\": true}"));

        PlenoWriteClient.StatusResult result = client.syncStatus("solicitacao", UUID.randomUUID());

        assertThat(result.status()).isEqualTo("sincronizado");
        assertThat(result.erro()).isNull();
    }

    @Test
    @DisplayName("syncStatus returns 'erro' on unsuccessful response")
    void syncStatus_unsuccessfulResponse_returnsError() throws Exception {
        when(httpGateway.execute(any(HttpGatewayRequest.class)))
                .thenReturn(new HttpGatewayResponse(200, null,
                        "{\"success\": false, \"error\": {\"message\": \"Resource not found\"}}"));

        PlenoWriteClient.StatusResult result = client.syncStatus("solicitacao", UUID.randomUUID());

        assertThat(result.status()).isEqualTo("erro");
        assertThat(result.erro()).contains("Resource not found");
    }

    @Test
    @DisplayName("syncStatus returns 'erro' on HTTP error")
    void syncStatus_httpError_returnsError() throws Exception {
        when(httpGateway.execute(any(HttpGatewayRequest.class)))
                .thenReturn(new HttpGatewayResponse(500, null, "Internal Server Error"));

        PlenoWriteClient.StatusResult result = client.syncStatus("vistoria", UUID.randomUUID());

        assertThat(result.status()).isEqualTo("erro");
        assertThat(result.erro()).contains("500");
    }

    @Test
    @DisplayName("syncStatus renews token if not available")
    void syncStatus_tokenNotAvailable_renewsToken() throws Exception {
        when(tokenStore.get()).thenReturn(null).thenReturn("renewed-token");
        when(httpGateway.execute(any(HttpGatewayRequest.class)))
                .thenReturn(new HttpGatewayResponse(200, null, "{\"success\": true}"));

        PlenoWriteClient.StatusResult result = client.syncStatus("solicitacao", UUID.randomUUID());

        verify(authService).renovar();
        assertThat(result.status()).isEqualTo("sincronizado");
    }

    @Test
    @DisplayName("syncStatus returns error when token cannot be obtained after renewal")
    void syncStatus_tokenUnavailable_returnsError() {
        when(tokenStore.get()).thenReturn(null).thenReturn(null);

        PlenoWriteClient.StatusResult result = client.syncStatus("solicitacao", UUID.randomUUID());

        assertThat(result.status()).isEqualTo("erro");
        assertThat(result.erro()).contains("Token Pleno");
    }

    @Test
    @DisplayName("syncStatus handles exception and returns error")
    void syncStatus_exception_returnsError() throws Exception {
        when(httpGateway.execute(any(HttpGatewayRequest.class)))
                .thenThrow(new RuntimeException("Connection timeout"));

        PlenoWriteClient.StatusResult result = client.syncStatus("solicitacao", UUID.randomUUID());

        assertThat(result.status()).isEqualTo("erro");
        assertThat(result.erro()).contains("Connection timeout");
    }

    @Test
    @DisplayName("syncStatus handles empty response body")
    void syncStatus_emptyBody_defaultsToError() throws Exception {
        when(httpGateway.execute(any(HttpGatewayRequest.class)))
                .thenReturn(new HttpGatewayResponse(200, null, ""));

        PlenoWriteClient.StatusResult result = client.syncStatus("vistoria", UUID.randomUUID());

        assertThat(result.status()).isEqualTo("erro");
    }

    @Test
    @DisplayName("4.7: syncStatus de imobiliaria/imovel/vistoriador nao chama a Pleno — usa o external_id local")
    void syncStatus_tipoSemShow_usaEstadoLocal() throws Exception {
        UUID imobiliariaId = UUID.randomUUID();
        UUID imovelId = UUID.randomUUID();
        IntegracaoSync sync = new IntegracaoSync(); sync.setExternalId("77");
        when(integracaoSync.buscar("imobiliaria", imobiliariaId)).thenReturn(Optional.of(sync));
        when(integracaoSync.buscar("imovel", imovelId)).thenReturn(Optional.empty());

        assertThat(client.syncStatus("imobiliaria", imobiliariaId).status()).isEqualTo("sincronizado");
        assertThat(client.syncStatus("imovel", imovelId).status()).isEqualTo("erro");
        verify(httpGateway, org.mockito.Mockito.never()).execute(any(HttpGatewayRequest.class));
    }

    @Test
    @DisplayName("syncStatus handles unknown error message")
    void syncStatus_unknownErrorMessage() throws Exception {
        when(httpGateway.execute(any(HttpGatewayRequest.class)))
                .thenReturn(new HttpGatewayResponse(200, null, "{\"success\": false, \"error\": {}}"));

        PlenoWriteClient.StatusResult result = client.syncStatus("solicitacao", UUID.randomUUID());

        assertThat(result.status()).isEqualTo("erro");
        assertThat(result.erro()).contains("erro desconhecido");
    }

    @Test
    @DisplayName("syncStatus makes a request to the gateway")
    void syncStatus_includesAuthorizationHeader() throws Exception {
        when(httpGateway.execute(any(HttpGatewayRequest.class)))
                .thenReturn(new HttpGatewayResponse(200, null, "{\"success\": true}"));

        client.syncStatus("solicitacao", UUID.randomUUID());

        verify(httpGateway).execute(any(HttpGatewayRequest.class));
    }

    @Test
    @DisplayName("syncStatus handles various resource types")
    void syncStatus_variousResourceTypes() throws Exception {
        when(httpGateway.execute(any(HttpGatewayRequest.class)))
                .thenReturn(new HttpGatewayResponse(200, null, "{\"success\": true}"));

        client.syncStatus("solicitacao", UUID.randomUUID());
        client.syncStatus("laudo", UUID.randomUUID());
        client.syncStatus("vistoria", UUID.randomUUID());
    }
}
