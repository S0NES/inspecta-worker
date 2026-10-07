package br.com.vistoria.worker.pdf;

import br.com.vistoria.core.modules.arquivo.Arquivo;
import br.com.vistoria.core.modules.arquivo.ArquivoService;
import br.com.vistoria.core.modules.laudo.Laudo;
import br.com.vistoria.core.modules.laudo.LaudoService;
import br.com.vistoria.worker.shared.Eventos;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class PdfConsumer {

    private final LaudoService laudoService;
    private final ArquivoService arquivoService;
    private final PdfRenderer renderer;
    private final LaudoHtmlBuilder htmlBuilder;

    @RabbitListener(queues = "q.pdf", containerFactory = "pdfListenerFactory")
    public void on(Message message) {
        JsonNode e = Eventos.parse(message.getBody());
        UUID laudoId = UUID.fromString(e.path("laudoId").asText());
        int versao = e.path("versao").asInt(1);

        Laudo laudo = laudoService.get(laudoId);
        if (laudo.getPdfArquivoId() != null) {
            log.info("[pdf] replay ignorado (laudo {} v{} ja possui pdf) - renderizacao duplicada", laudoId, versao);
            return;
        }

        byte[] pdf = renderer.render(htmlBuilder.html(laudo));

        String key = "laudos/" + laudoId + "/v" + versao + ".pdf";
        Arquivo arquivo = arquivoService.registrar(key, "application/pdf", (long) pdf.length, "laudo", laudoId, true);
        arquivoService.salvarConteudo(arquivo.getId(), pdf);
        laudoService.marcarPdf(laudoId, arquivo.getId());
        log.info("PDF laudo {} v{} ok ({} bytes)", laudoId, versao, pdf.length);
    }
}
