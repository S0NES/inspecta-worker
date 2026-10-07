package br.com.vistoria.worker.pleno;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Projecao do que o inspecta-worker precisa de um item da listagem
 * {@code GET /api/vistoria/solicitacao} da Pleno — "a volta" do ciclo.
 *
 * <p>Parsing defensivo em {@link #from(JsonNode)}: se o shape do payload da Pleno mudar
 * ({@code status_solicitacao} sumir, virar array, etc.) o parser <b>lanca</b> apontando o
 * campo — nunca falha em silencio em prod ({@code PLENO_INTEGRATION_PROMPT.md} § 10.5).</p>
 *
 * @param solCodigo    {@code sol_codigo} — casa com {@code integracao_sync.external_id} de {@code solicitacao}
 * @param staSolCodigo {@code status_solicitacao.sta_sol_codigo} (NAO {@code sol_sta_codigo} de raiz)
 * @param visCodigo    {@code vistoria.vis_codigo} quando ja ha vistoria (para o fallback {@code /vistoria/{id}/show})
 * @param linkPdf      {@code vistoria.link_pdf} — resultado desta solicitacao. NUNCA vem de {@code vistoria_modelo} (so template)
 */
public record PlenoSolicitacaoItem(String solCodigo, Integer staSolCodigo, String visCodigo, String linkPdf) {

    /** Sentinela de contrato: o parser lanca isso quando o payload da Pleno muda de forma. */
    public static class ContratoPlenoException extends IllegalStateException {
        public ContratoPlenoException(String message) { super(message); }
    }

    public static PlenoSolicitacaoItem from(JsonNode item) {
        if (item == null || !item.isObject()) {
            throw new ContratoPlenoException(
                    "payload Pleno: item de /api/vistoria/solicitacao nao e objeto: " + item);
        }

        String solCodigo = textoObrigatorio(item, "sol_codigo");

        JsonNode statusNode = item.get("status_solicitacao");
        if (statusNode == null || !statusNode.isObject()) {
            throw new ContratoPlenoException(
                    "payload Pleno mudou: item sol_codigo=" + solCodigo
                            + " sem objeto 'status_solicitacao' (esperado {sta_sol_codigo:int}). "
                            + "NAO use 'sol_sta_codigo' de raiz.");
        }
        JsonNode sta = statusNode.get("sta_sol_codigo");
        Integer staSolCodigo = (sta != null && sta.canConvertToInt()) ? sta.asInt() : null;

        // item.vistoria = resultado real. item.vistoria_modelo = so template — NUNCA ler link_pdf dele.
        JsonNode vistoria = item.get("vistoria");
        String visCodigo = null;
        String linkPdf = null;
        if (vistoria != null && vistoria.isObject()) {
            visCodigo = texto(vistoria, "vis_codigo");
            linkPdf = texto(vistoria, "link_pdf");
        }

        return new PlenoSolicitacaoItem(solCodigo, staSolCodigo, visCodigo, linkPdf);
    }

    public boolean temLinkPdf() {
        return linkPdf != null && !linkPdf.isBlank();
    }

    private static String texto(JsonNode node, String campo) {
        JsonNode n = node.get(campo);
        if (n == null || n.isNull() || n.isMissingNode()) return null;
        String s = n.asText();
        return s == null || s.isBlank() ? null : s;
    }

    private static String textoObrigatorio(JsonNode node, String campo) {
        String v = texto(node, campo);
        if (v == null) {
            throw new ContratoPlenoException(
                    "payload Pleno mudou: item de /api/vistoria/solicitacao sem '" + campo + "'");
        }
        return v;
    }
}
