package br.com.vistoria.worker.signature;

import br.com.vistoria.core.events.EventTypes;
import br.com.vistoria.core.modules.assinatura.AssinaturaService;
import br.com.vistoria.core.modules.assinatura.Signatario;
import br.com.vistoria.core.modules.laudo.Laudo;
import br.com.vistoria.core.modules.laudo.LaudoService;
import br.com.vistoria.core.shared.outbox.OutboxService;
import br.com.vistoria.worker.shared.Eventos;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * q.signature &lt;- jobs.signature.send. **Só o PREPARO.** O inspecta-worker resolve o laudo + os
 * signatários, garante que o PDF já existe, e enfileira {@code jobs.signature.create-document}
 * — quem monta e envia o multipart pra Autentique é o {@code inspecta-messenger} ({@code SignatureCreateConsumer}).
 *
 * <p>Sem PDF ainda -&gt; relança: o retry da fila reprocessa até o {@code PdfConsumer} terminar
 * (dead-letter após as tentativas).</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SignatureConsumer {

    private final AssinaturaService assinaturaService;
    private final LaudoService laudoService;
    private final OutboxService outbox;

    @RabbitListener(queues = "q.signature", containerFactory = "signatureListenerFactory")
    @Transactional
    public void on(Message message) {
        String type = message.getMessageProperties().getReceivedRoutingKey();
        if (!EventTypes.SIGNATURE_REQUESTED.equals(type)) {
            log.warn("[signature] routing key inesperada em q.signature: {}", type);
            return;
        }
        JsonNode e = Eventos.parse(message.getBody());
        String tipo = e.path("documentoTipo").asText();
        UUID id = UUID.fromString(e.path("documentoId").asText());

        Laudo laudo = laudoService.get(id);
        if (laudo.getPdfArquivoId() == null)
            throw new IllegalStateException("laudo " + id + " sem PDF gerado ainda - retry");

        List<Signatario> partes = assinaturaService.signatariosDoDocumento(tipo, id);
        if (partes.isEmpty()) {
            log.warn("[signature] {}/{} sem signatarios - nada a enviar", tipo, id);
            return;
        }

        List<Map<String, Object>> signers = partes.stream()
                .map(s -> Map.<String, Object>of("signatarioId", s.getId(), "nome", s.getNome()))
                .toList();

        outbox.publish(EventTypes.SIGNATURE_CREATE_DOCUMENT, Map.of(
                "eventId", "sigdoc-" + tipo + "-" + id,
                "documentoTipo", tipo,
                "documentoId", id,
                "pdfArquivoId", laudo.getPdfArquivoId(),
                "documentName", "Laudo " + id,
                "signers", signers));
        log.info("[signature] preparo {}/{} -> jobs.signature.create-document ({} signers)", tipo, id, signers.size());
    }
}
