package br.com.vistoria.worker.pleno;

import br.com.vistoria.core.modules.integracao.PlenoImportacaoVistoria;

import java.time.Duration;

/**
 * Classificacao do {@code status_solicitacao.sta_sol_codigo} da Pleno (T6, "a volta" —
 * {@code PLENO_INTEGRATION_PROMPT.md} § 3, {@code fase-6-pleno.md}).
 *
 * <p>Atencao: o codigo que vale e {@code status_solicitacao.sta_sol_codigo}, <b>nunca</b> o
 * {@code sol_sta_codigo} de raiz do item (FK de juncao interna da Pleno).</p>
 *
 * <p>{@link #fromCodigo(Integer)} nunca lanca — codigo nulo ou fora da tabela vira
 * {@link #DESCONHECIDO} (alerta + tratado como {@link #EM_ANDAMENTO}).</p>
 */
public enum StatusVistoriaPleno {

    SOLICITADA,
    DESIGNADA,
    EM_ANDAMENTO,
    EM_AUDITORIA,
    CONCLUIDA,
    CANCELADA,
    DESCONHECIDO;

    /** Tabela § 3: 1 SOLICITADA, 2 DESIGNADA, 3 EM_ANDAMENTO, 6 EM_AUDITORIA, 4 CONCLUIDA, 5 CANCELADA. */
    public static StatusVistoriaPleno fromCodigo(Integer staSolCodigo) {
        if (staSolCodigo == null) return DESCONHECIDO;
        return switch (staSolCodigo) {
            case 1 -> SOLICITADA;
            case 2 -> DESIGNADA;
            case 3 -> EM_ANDAMENTO;
            case 6 -> EM_AUDITORIA;
            case 4 -> CONCLUIDA;
            case 5 -> CANCELADA;
            default -> DESCONHECIDO;
        };
    }

    public boolean terminal() {
        return this == CONCLUIDA || this == CANCELADA;
    }

    /**
     * Backoff por fase (nao por idade simples) — {@code PLENO_INTEGRATION_PROMPT.md} § 5.5.
     * Quanto mais perto do fim, mais frequente a verificacao.
     */
    public Duration proximaVerificacao() {
        return switch (this) {
            case SOLICITADA, DESIGNADA -> Duration.ofHours(6);
            case EM_ANDAMENTO           -> Duration.ofHours(2);
            case EM_AUDITORIA           -> Duration.ofMinutes(30);
            case DESCONHECIDO           -> Duration.ofHours(1);
            default                     -> Duration.ofHours(4);
        };
    }

    /**
     * Estado de importacao a gravar enquanto a vistoria ainda esta em andamento
     * (o terminal — PDF baixado, cancelada — e tratado direto pelo cron/ingestion).
     */
    public PlenoImportacaoVistoria.Status statusImportacaoEmAndamento() {
        return this == SOLICITADA
                ? PlenoImportacaoVistoria.Status.SOLICITADA
                : PlenoImportacaoVistoria.Status.EM_ANDAMENTO;
    }
}
