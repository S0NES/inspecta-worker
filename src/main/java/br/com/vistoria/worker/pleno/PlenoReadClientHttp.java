package br.com.vistoria.worker.pleno;

import br.com.vistoria.core.gateway.HttpGateway;
import br.com.vistoria.core.gateway.HttpGatewayRequest;
import br.com.vistoria.core.gateway.HttpGatewayResponse;
import br.com.vistoria.core.modules.integracao.PlenoAuthService;
import br.com.vistoria.core.modules.integracao.PlenoTokenStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code inspecta.pleno.mode=http}: leitura real na Pleno para o poller de importacao.
 *
 * <p>Mesma mecanica HTTP/auth do {@code PlenoWriteClientHttp} (Bearer via {@link PlenoTokenStore}
 * + {@link PlenoAuthService#renovar()}, envelope {@code {success,data,error}} onde {@code HTTP 2xx}
 * <b>nao</b> e sucesso). Nao compartilha codigo com o write client de proposito — aquele e
 * intocavel (regra do pacote de trabalho); a duplicacao aqui e pequena e isolada a leitura.</p>
 */
@Component("plenoReadHttp")
@Slf4j
public class PlenoReadClientHttp implements PlenoReadClient {

    private static final DateTimeFormatter DATA = DateTimeFormatter.ISO_LOCAL_DATE;

    private final PlenoTokenStore tokenStore;
    private final PlenoAuthService authService;
    private final HttpGateway httpGateway;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${inspecta.pleno.base-url:}") private String baseUrl;

    public PlenoReadClientHttp(PlenoTokenStore tokenStore, PlenoAuthService authService, HttpGateway httpGateway) {
        this.tokenStore = tokenStore;
        this.authService = authService;
        this.httpGateway = httpGateway;
    }

    private String token() {
        String t = tokenStore.get();
        if (t == null) {
            authService.renovar();
            t = tokenStore.get();
        }
        if (t == null) {
            throw new IllegalStateException("Token Pleno indisponivel — auth de integrador falhou");
        }
        return t;
    }

    @Override
    public List<PlenoSolicitacaoItem> listarSolicitacoes(LocalDate desde, LocalDate ate, int pagina) {
        // filters[periodo][0] / [1] — brackets pre-encodados; page 1-based.
        String path = "/api/vistoria/solicitacao"
                + "?filters%5Bperiodo%5D%5B0%5D=" + DATA.format(desde)
                + "&filters%5Bperiodo%5D%5B1%5D=" + DATA.format(ate)
                + "&page=" + pagina;
        JsonNode data = get(path);
        List<PlenoSolicitacaoItem> itens = new ArrayList<>();
        for (JsonNode node : listaDeItens(data)) {
            itens.add(PlenoSolicitacaoItem.from(node));
        }
        return itens;
    }

    @Override
    public Optional<String> linkPdfDaVistoria(String visCodigo) {
        JsonNode data = get("/api/vistoria/vistoria/" + visCodigo + "/show");
        if (data == null) return Optional.empty();
        JsonNode link = data.get("link_pdf");
        if (link == null || link.isNull() || link.asText().isBlank()) return Optional.empty();
        return Optional.of(link.asText());
    }

    /** A listagem pode vir como array direto em {@code data} ou como paginador {@code data.data}. */
    private static Iterable<JsonNode> listaDeItens(JsonNode data) {
        if (data == null) return List.of();
        if (data.isArray()) return data;
        JsonNode inner = data.get("data");
        if (inner != null && inner.isArray()) return inner;
        return List.of();
    }

    private JsonNode get(String path) {
        HttpGatewayResponse res;
        try {
            res = httpGateway.execute(HttpGatewayRequest.get(
                    URI.create(baseUrl + path),
                    Map.of("Authorization", "Bearer " + token(), "Accept", "application/json")));
        } catch (Exception e) {
            throw new IllegalStateException("Pleno GET " + path + " falhou: " + e, e);
        }
        JsonNode body;
        try {
            String raw = res.body();
            body = mapper.readTree(raw == null || raw.isBlank() ? "{}" : raw);
        } catch (Exception e) {
            throw new IllegalStateException("Pleno GET " + path + ": corpo ilegivel (HTTP " + res.status() + ")", e);
        }
        if (!body.path("success").asBoolean(false)) {
            String msg = body.path("error").path("message").asText(
                    body.path("message").asText("HTTP " + res.status()));
            throw new IllegalStateException("Pleno GET " + path + " recusou: " + msg);
        }
        return body.path("data");
    }
}
