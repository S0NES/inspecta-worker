package br.com.vistoria.worker.pleno;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * {@code inspecta.pleno.mode=mock}: nao toca a Pleno. Listagem sempre vazia (o poller nao faz
 * nada), igual ao {@code PlenoWriteClientMock}. Testes de fluxo injetam um mock do Mockito
 * no lugar deste.
 */
@Component("plenoReadMock")
@Slf4j
public class PlenoReadClientMock implements PlenoReadClient {

    @Override
    public List<PlenoSolicitacaoItem> listarSolicitacoes(LocalDate desde, LocalDate ate, int pagina) {
        log.debug("[pleno-read-mock] listarSolicitacoes({}, {}, p{}) -> []", desde, ate, pagina);
        return List.of();
    }

    @Override
    public Optional<String> linkPdfDaVistoria(String visCodigo) {
        log.debug("[pleno-read-mock] linkPdfDaVistoria({}) -> vazio", visCodigo);
        return Optional.empty();
    }
}
