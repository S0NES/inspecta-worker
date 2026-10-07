package br.com.vistoria.worker.pleno;

import br.com.vistoria.core.modules.integracao.IntegracaoSyncService;
import br.com.vistoria.worker.shared.Eventos;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Slf4j
public class PlenoSyncConsumer {

    private final PlenoWriteClient mock;
    private final PlenoWriteClient http;
    private final IntegracaoSyncService integracaoSync;

    @Value("${inspecta.pleno.mode:mock}") private String modo;

    public PlenoSyncConsumer(@Qualifier("plenoWriteMock") PlenoWriteClient mock,
                             @Qualifier("plenoWriteHttp") PlenoWriteClient http,
                             IntegracaoSyncService integracaoSync) {
        this.mock = mock;
        this.http = http;
        this.integracaoSync = integracaoSync;
    }

    private PlenoWriteClient client() {
        if ("http".equals(modo)) return http;
        if ("mock".equals(modo)) return mock;
        throw new IllegalStateException("Invalid inspecta.pleno.mode: '" + modo + "'. Valid values: http, mock");
    }

    @RabbitListener(queues = "q.pleno", containerFactory = "plenoListenerFactory")
    public void on(Message message) {
        JsonNode e = Eventos.parse(message.getBody());
        UUID syncId = UUID.fromString(e.path("integracaoSyncId").asText());
        String recursoTipo = e.path("recursoTipo").asText();
        UUID recursoId = UUID.fromString(e.path("recursoId").asText());
        String acao = e.path("acao").asText("sync_status");

        try {
            if ("espelhar".equals(acao)) {
                var r = client().espelhar(recursoTipo, recursoId);
                integracaoSync.aplicarResultado(syncId, "sincronizado", r.externalId(), null);
            } else {
                var r = client().syncStatus(recursoTipo, recursoId);
                integracaoSync.aplicarResultado(syncId, r.status(), null, r.erro());
            }
        } catch (Exception ex) {
            integracaoSync.aplicarResultado(syncId, "erro", null, ex.getMessage());
            throw ex;
        }
    }
}
