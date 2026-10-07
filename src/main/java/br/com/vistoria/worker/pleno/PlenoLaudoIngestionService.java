package br.com.vistoria.worker.pleno;

import br.com.vistoria.core.events.EventTypes;
import br.com.vistoria.core.gateway.HttpGateway;
import br.com.vistoria.core.gateway.HttpGatewayRequest;
import br.com.vistoria.core.gateway.HttpGatewayResponse;
import br.com.vistoria.core.modules.arquivo.Arquivo;
import br.com.vistoria.core.modules.arquivo.ArquivoService;
import br.com.vistoria.core.modules.integracao.PlenoImportacaoService;
import br.com.vistoria.core.modules.integracao.PlenoImportacaoVistoria;
import br.com.vistoria.core.modules.laudo.Laudo;
import br.com.vistoria.core.modules.laudo.LaudoRepository;
import br.com.vistoria.core.modules.laudo.LaudoService;
import br.com.vistoria.core.shared.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Ingestao de um PDF de vistoria ja pronto vindo da Pleno para dentro do pipeline de laudo/
 * assinatura do Inspecta ({@code PLENO_INTEGRATION_PROMPT.md} § 6). Reusa os servicos existentes —
 * <b>nenhum bucket/storage novo</b>: {@code arquivoService.salvarConteudo} ja publica
 * {@code jobs.storage.upload-requested}.
 *
 * <p><b>Idempotente</b> (rule 4). Reentrancia:</p>
 * <ul>
 *   <li>{@code enviado_para_assinatura_em != null} -> nada a fazer (nao republica assinatura);</li>
 *   <li>{@code pdf_arquivo_id != null} -> PDF ja baixado, nao rebaixa nem duplica {@code arquivos}/
 *       {@code laudos}; so garante o disparo da assinatura se ainda faltou;</li>
 *   <li>sha256 igual ao gravado -> no-op no download.</li>
 * </ul>
 *
 * <p>O download HTTP roda dentro da {@code @Transactional} de proposito: o link da Pleno pode
 * expirar (baixar imediatamente) e o volume e baixo (cron de 15 min). A conexao fica presa
 * durante o download — aceito para esse cadence.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlenoLaudoIngestionService {

    private final HttpGateway httpGateway;
    private final ArquivoService arquivoService;
    private final LaudoService laudoService;
    private final LaudoRepository laudoRepository;
    private final PlenoImportacaoService importacao;
    private final OutboxService outbox;

    /**
     * @param solicitacaoServicoId chave natural de {@code pleno_importacao_vistoria}
     * @param linkPdf              {@code vistoria.link_pdf} (nunca de {@code vistoria_modelo})
     */
    @Transactional
    public void ingerir(UUID solicitacaoServicoId, String linkPdf) {
        PlenoImportacaoVistoria row = importacao.buscar(solicitacaoServicoId).orElseThrow(
                () -> new IllegalStateException("pleno_importacao_vistoria ausente para servico " + solicitacaoServicoId));

        if (row.getEnviadoParaAssinaturaEm() != null) {
            log.debug("[pleno-ingestao] servico {} ja enviado para assinatura em {} — no-op",
                    solicitacaoServicoId, row.getEnviadoParaAssinaturaEm());
            return;
        }

        UUID laudoId = row.getLaudoId();

        if (row.getPdfArquivoId() == null) {
            byte[] pdf = baixar(linkPdf);
            String sha = sha256(pdf);

            if (sha.equals(row.getPdfSha256())) {
                log.info("[pleno-ingestao] servico {} sha256 inalterado ({}), pulando gravacao do arquivo",
                        solicitacaoServicoId, sha);
            } else {
                Laudo laudo = laudoRepository
                        .findFirstBySolicitacaoServicoIdOrderByVersaoDesc(solicitacaoServicoId)
                        .orElseGet(() -> laudoService.criar(solicitacaoServicoId));
                laudoId = laudo.getId();

                String key = "laudos/" + laudoId + "/pleno-v" + laudo.getVersao() + ".pdf";
                Arquivo arquivo = arquivoService.registrar(
                        key, "application/pdf", (long) pdf.length, "laudo", laudoId, /*processado*/ true);
                arquivoService.salvarConteudo(arquivo.getId(), pdf);

                laudoService.marcarPdf(laudoId, arquivo.getId());
                laudoService.finalizarComPdfExterno(laudoId, "importado_pleno");

                importacao.marcarPdfBaixado(solicitacaoServicoId, arquivo.getId(), sha, laudoId);
                log.info("[pleno-ingestao] servico {} PDF importado ({} bytes) -> laudo {} arquivo {}",
                        solicitacaoServicoId, pdf.length, laudoId, arquivo.getId());
            }
        }

        if (laudoId == null) {
            // pdf_arquivo_id preenchido mas laudo_id nulo: estado inconsistente de uma versao
            // anterior — recupera pelo servico.
            laudoId = laudoRepository.findFirstBySolicitacaoServicoIdOrderByVersaoDesc(solicitacaoServicoId)
                    .map(Laudo::getId)
                    .orElseThrow(() -> new IllegalStateException(
                            "servico " + solicitacaoServicoId + " com pdf_arquivo_id mas sem laudo"));
        }

        // jobs.signature.send — MESMO evento/consumidor (SignatureConsumer) de qualquer laudo.
        // eventId estavel = guarda de replay downstream.
        outbox.publish(EventTypes.SIGNATURE_REQUESTED, Map.of(
                "eventId", "signature-" + laudoId,
                "documentoTipo", "laudo",
                "documentoId", laudoId));
        importacao.marcarEnviadoParaAssinatura(solicitacaoServicoId);
        log.info("[pleno-ingestao] servico {} -> assinatura disparada (laudo {})", solicitacaoServicoId, laudoId);
    }

    private byte[] baixar(String linkPdf) {
        if (linkPdf == null || linkPdf.isBlank()) {
            throw new IllegalStateException("link_pdf vazio");
        }
        HttpGatewayResponse res;
        try {
            res = httpGateway.execute(HttpGatewayRequest.getBinario(URI.create(linkPdf), Map.of()));
        } catch (Exception e) {
            throw new IllegalStateException("download do PDF Pleno falhou: " + e, e);
        }
        if (!res.sucesso()) {
            throw new IllegalStateException("download do PDF Pleno HTTP " + res.status());
        }
        byte[] bytes = res.bodyComoBytes();
        if (bytes == null || bytes.length == 0) {
            throw new IllegalStateException("download do PDF Pleno veio vazio");
        }
        return bytes;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("sha256 indisponivel", e);
        }
    }
}
