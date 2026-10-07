package br.com.vistoria.worker.pleno;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Leitura de status/PDF de vistoria na Pleno — "a volta" do ciclo de espelhamento.
 *
 * <p>Par de leitura do {@code PlenoWriteClient} (escrita, ja existente). Dois beans
 * ({@code plenoReadMock} / {@code plenoReadHttp}); {@link PlenoImportacaoStatusCron}
 * escolhe por {@code inspecta.pleno.mode} no mesmo padrao {@code Map}/{@code @Qualifier}
 * de {@code PlenoSyncConsumer} — sem {@code @ConditionalOnProperty}.</p>
 */
public interface PlenoReadClient {

    /**
     * Pagina {@code GET /api/vistoria/solicitacao} filtrando pelo periodo {@code [desde, ate]}.
     * {@code pagina} e 1-based. Lista vazia = fim da paginacao.
     */
    List<PlenoSolicitacaoItem> listarSolicitacoes(LocalDate desde, LocalDate ate, int pagina);

    /**
     * Fallback para {@code CONCLUIDA} sem {@code link_pdf} na listagem:
     * {@code GET /api/vistoria/vistoria/{visCodigo}/show} -> {@code data.link_pdf}.
     */
    Optional<String> linkPdfDaVistoria(String visCodigo);
}
