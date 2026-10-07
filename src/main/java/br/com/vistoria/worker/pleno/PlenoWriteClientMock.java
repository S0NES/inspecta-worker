package br.com.vistoria.worker.pleno;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component("plenoWriteMock")
@Slf4j
public class PlenoWriteClientMock implements PlenoWriteClient {

    @Override
    public WriteResult espelhar(String recursoTipo, UUID recursoId) {
        String externalId = "mock-" + recursoTipo + "-" + recursoId;
        log.info("[pleno-mock] espelhar {}/{} -> {}", recursoTipo, recursoId, externalId);
        return new WriteResult(externalId);
    }

    @Override
    public StatusResult syncStatus(String recursoTipo, UUID recursoId) {
        log.info("[pleno-mock] sync_status {}/{}", recursoTipo, recursoId);
        return new StatusResult("sincronizado", null);
    }
}
