package br.com.vistoria.worker.pleno;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PlenoSolicitacaoItem.from — parsing defensivo da listagem Pleno")
class PlenoSolicitacaoItemTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String s) {
        try {
            return mapper.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("le sta_sol_codigo de status_solicitacao, NAO de sol_sta_codigo de raiz")
    void leStatusDoObjetoCerto() {
        PlenoSolicitacaoItem item = PlenoSolicitacaoItem.from(json("""
            {
              "sol_codigo": 8123,
              "sol_sta_codigo": 99,
              "status_solicitacao": { "sta_sol_codigo": 3 }
            }
            """));

        assertThat(item.solCodigo()).isEqualTo("8123");
        assertThat(item.staSolCodigo()).isEqualTo(3);
        assertThat(StatusVistoriaPleno.fromCodigo(item.staSolCodigo())).isEqualTo(StatusVistoriaPleno.EM_ANDAMENTO);
    }

    @Test
    @DisplayName("link_pdf vem de item.vistoria — NUNCA de item.vistoria_modelo (so template)")
    void linkPdfVemDaVistoriaNaoDoModelo() {
        PlenoSolicitacaoItem item = PlenoSolicitacaoItem.from(json("""
            {
              "sol_codigo": "8123",
              "status_solicitacao": { "sta_sol_codigo": 4 },
              "vistoria_modelo": { "vis_codigo": "MODELO-1", "link_pdf": "https://pleno/modelo-NAO-USAR.pdf" },
              "vistoria": { "vis_codigo": "V-555", "link_pdf": "https://pleno/real/laudo-555.pdf" }
            }
            """));

        assertThat(item.linkPdf()).isEqualTo("https://pleno/real/laudo-555.pdf");
        assertThat(item.visCodigo()).isEqualTo("V-555");
        assertThat(item.temLinkPdf()).isTrue();
    }

    @Test
    @DisplayName("so vistoria_modelo presente -> sem link_pdf (nao vaza o do template)")
    void apenasVistoriaModelo_semLinkPdf() {
        PlenoSolicitacaoItem item = PlenoSolicitacaoItem.from(json("""
            {
              "sol_codigo": "8123",
              "status_solicitacao": { "sta_sol_codigo": 3 },
              "vistoria_modelo": { "vis_codigo": "MODELO-1", "link_pdf": "https://pleno/modelo.pdf" }
            }
            """));

        assertThat(item.linkPdf()).isNull();
        assertThat(item.visCodigo()).isNull();
        assertThat(item.temLinkPdf()).isFalse();
    }

    @Test
    @DisplayName("CONCLUIDA sem link_pdf ainda: vistoria presente mas link nulo")
    void concluidaSemLinkPdf() {
        PlenoSolicitacaoItem item = PlenoSolicitacaoItem.from(json("""
            {
              "sol_codigo": "8123",
              "status_solicitacao": { "sta_sol_codigo": 4 },
              "vistoria": { "vis_codigo": "V-9" }
            }
            """));

        assertThat(item.temLinkPdf()).isFalse();
        assertThat(item.visCodigo()).isEqualTo("V-9");
    }

    @Test
    @DisplayName("CONTRATO: status_solicitacao ausente -> lanca apontando o campo (nao falha em silencio)")
    void contrato_statusSolicitacaoAusente() {
        assertThatThrownBy(() -> PlenoSolicitacaoItem.from(json("""
            { "sol_codigo": "8123", "sol_sta_codigo": 3 }
            """)))
                .isInstanceOf(PlenoSolicitacaoItem.ContratoPlenoException.class)
                .hasMessageContaining("status_solicitacao")
                .hasMessageContaining("sol_sta_codigo");
    }

    @Test
    @DisplayName("CONTRATO: status_solicitacao virou array -> lanca")
    void contrato_statusSolicitacaoMudouDeShape() {
        assertThatThrownBy(() -> PlenoSolicitacaoItem.from(json("""
            { "sol_codigo": "8123", "status_solicitacao": [ { "sta_sol_codigo": 3 } ] }
            """)))
                .isInstanceOf(PlenoSolicitacaoItem.ContratoPlenoException.class)
                .hasMessageContaining("status_solicitacao");
    }

    @Test
    @DisplayName("CONTRATO: sol_codigo ausente -> lanca")
    void contrato_solCodigoAusente() {
        assertThatThrownBy(() -> PlenoSolicitacaoItem.from(json("""
            { "status_solicitacao": { "sta_sol_codigo": 3 } }
            """)))
                .isInstanceOf(PlenoSolicitacaoItem.ContratoPlenoException.class)
                .hasMessageContaining("sol_codigo");
    }

    @Test
    @DisplayName("sta_sol_codigo nao numerico -> null (classificador trata como DESCONHECIDO)")
    void staSolCodigoNaoNumerico() {
        PlenoSolicitacaoItem item = PlenoSolicitacaoItem.from(json("""
            { "sol_codigo": "1", "status_solicitacao": { "sta_sol_codigo": "???" } }
            """));
        assertThat(item.staSolCodigo()).isNull();
    }
}
