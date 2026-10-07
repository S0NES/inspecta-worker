package br.com.vistoria.worker.pdf;

import br.com.vistoria.core.modules.laudo.Laudo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public final class LaudoTemplate {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LaudoTemplate() {}

    public static String render(Laudo laudo) {
        return render(laudo, null, null);
    }

    /**
     * @param qrCodeDataUri QR code da galeria de fotos como {@code data:image/png;base64,...},
     *                      ou {@code null}/vazio se o laudo nao tem fotos ou o link publico
     *                      nao pode ser emitido — nesse caso a secao de galeria e omitida.
     * @param galeriaUrl URL da pagina publica que o QR aponta, exibida como texto de apoio
     *                   (o QR pode falhar ao ser lido; o link por extenso e o fallback).
     */
    public static String render(Laudo laudo, String qrCodeDataUri, String galeriaUrl) {
        JsonNode conteudo;
        try {
            conteudo = MAPPER.readTree(laudo.getConteudoJson());
        } catch (Exception e) {
            throw new IllegalArgumentException("conteudo_json invalido laudo " + laudo.getId(), e);
        }

        StringBuilder ambientes = new StringBuilder();
        for (JsonNode amb : conteudo.path("ambientes")) {
            ambientes.append("<section><h2>").append(esc(amb.path("nome").asText("Ambiente"))).append("</h2>");
            for (JsonNode item : amb.path("itens")) {
                ambientes.append("<div class=\"item\"><h3>").append(esc(item.path("descricao").asText(""))).append("</h3>")
                        .append("<p><strong>Estado:</strong> ").append(esc(item.path("estado").asText("-"))).append("</p>");
                if (item.hasNonNull("observacao"))
                    ambientes.append("<p>").append(esc(item.path("observacao").asText())).append("</p>");
                ambientes.append("</div>");
            }
            ambientes.append("</section>");
        }

        String galeria = qrCodeDataUri == null || qrCodeDataUri.isBlank() ? "" : """
                <section class="galeria">
                  <h2>Fotos da vistoria</h2>
                  <p>Escaneie o QR code para ver todas as fotos em alta resolucao:</p>
                  <img src="%s" alt="QR code da galeria de fotos" width="140" height="140"/>
                  <p class="url">%s</p>
                </section>
                """.formatted(qrCodeDataUri, esc(galeriaUrl == null ? "" : galeriaUrl));

        return """
                <!DOCTYPE html><html lang="pt-BR"><head><meta charset="utf-8">
                <style>
                  body { font-family: Arial, sans-serif; color: #222; font-size: 12px; }
                  h1 { font-size: 20px; border-bottom: 2px solid #333; padding-bottom: 8px; }
                  h2 { font-size: 16px; margin-top: 24px; background: #f0f0f0; padding: 6px 10px; }
                  .item { margin: 10px 0 14px 10px; page-break-inside: avoid; }
                  .galeria { page-break-inside: avoid; }
                  .galeria .url { font-size: 10px; color: #555; word-break: break-all; }
                  footer { margin-top: 40px; font-size: 10px; color: #777; }
                </style></head><body>
                <h1>Laudo de Vistoria — v%d</h1>
                <p>Servico: %s · Emitido: %s</p>
                %s
                %s
                <footer>Documento gerado pela plataforma Inspecta.</footer>
                </body></html>
                """.formatted(laudo.getVersao(), laudo.getSolicitacaoServicoId(),
                LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")), ambientes, galeria);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
