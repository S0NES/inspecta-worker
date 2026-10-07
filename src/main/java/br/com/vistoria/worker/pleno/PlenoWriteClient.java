package br.com.vistoria.worker.pleno;

import java.util.UUID;

public interface PlenoWriteClient {

    record WriteResult(String externalId) {}
    record StatusResult(String status, String erro) {}

    WriteResult espelhar(String recursoTipo, UUID recursoId);

    StatusResult syncStatus(String recursoTipo, UUID recursoId);
}
