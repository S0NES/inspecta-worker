package br.com.vistoria.worker.pleno;

import br.com.vistoria.core.modules.integracao.PlenoImportacaoVistoria;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("StatusVistoriaPleno — classificador de sta_sol_codigo")
class StatusVistoriaPlenoTest {

    @ParameterizedTest(name = "sta_sol_codigo {0} -> {1}")
    @CsvSource({
            "1, SOLICITADA",
            "2, DESIGNADA",
            "3, EM_ANDAMENTO",
            "6, EM_AUDITORIA",
            "4, CONCLUIDA",
            "5, CANCELADA",
    })
    void classificaOsSeisCodigosConhecidos(int codigo, StatusVistoriaPleno esperado) {
        assertThat(StatusVistoriaPleno.fromCodigo(codigo)).isEqualTo(esperado);
    }

    @ParameterizedTest(name = "codigo desconhecido {0} -> DESCONHECIDO (nunca excecao)")
    @ValueSource(ints = {0, 7, 8, 99, -1, 42})
    void codigoDesconhecidoViraDesconhecidoSemLancar(int codigo) {
        assertThatCode(() -> {
            assertThat(StatusVistoriaPleno.fromCodigo(codigo)).isEqualTo(StatusVistoriaPleno.DESCONHECIDO);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("codigo nulo -> DESCONHECIDO, sem NPE")
    void codigoNuloViraDesconhecido() {
        assertThat(StatusVistoriaPleno.fromCodigo(null)).isEqualTo(StatusVistoriaPleno.DESCONHECIDO);
    }

    @Test
    @DisplayName("terminal(): so CONCLUIDA e CANCELADA")
    void terminal() {
        assertThat(StatusVistoriaPleno.CONCLUIDA.terminal()).isTrue();
        assertThat(StatusVistoriaPleno.CANCELADA.terminal()).isTrue();
        assertThat(StatusVistoriaPleno.SOLICITADA.terminal()).isFalse();
        assertThat(StatusVistoriaPleno.EM_ANDAMENTO.terminal()).isFalse();
        assertThat(StatusVistoriaPleno.EM_AUDITORIA.terminal()).isFalse();
        assertThat(StatusVistoriaPleno.DESCONHECIDO.terminal()).isFalse();
    }

    @Test
    @DisplayName("backoff por fase (§ 5.5)")
    void backoffPorFase() {
        assertThat(StatusVistoriaPleno.SOLICITADA.proximaVerificacao()).isEqualTo(Duration.ofHours(6));
        assertThat(StatusVistoriaPleno.DESIGNADA.proximaVerificacao()).isEqualTo(Duration.ofHours(6));
        assertThat(StatusVistoriaPleno.EM_ANDAMENTO.proximaVerificacao()).isEqualTo(Duration.ofHours(2));
        assertThat(StatusVistoriaPleno.EM_AUDITORIA.proximaVerificacao()).isEqualTo(Duration.ofMinutes(30));
        assertThat(StatusVistoriaPleno.DESCONHECIDO.proximaVerificacao()).isEqualTo(Duration.ofHours(1));
        assertThat(StatusVistoriaPleno.CONCLUIDA.proximaVerificacao()).isEqualTo(Duration.ofHours(4));
    }

    @Test
    @DisplayName("statusImportacaoEmAndamento: SOLICITADA mapeia p/ SOLICITADA, resto p/ EM_ANDAMENTO")
    void mapaImportacao() {
        assertThat(StatusVistoriaPleno.SOLICITADA.statusImportacaoEmAndamento())
                .isEqualTo(PlenoImportacaoVistoria.Status.SOLICITADA);
        assertThat(StatusVistoriaPleno.DESIGNADA.statusImportacaoEmAndamento())
                .isEqualTo(PlenoImportacaoVistoria.Status.EM_ANDAMENTO);
        assertThat(StatusVistoriaPleno.EM_AUDITORIA.statusImportacaoEmAndamento())
                .isEqualTo(PlenoImportacaoVistoria.Status.EM_ANDAMENTO);
        assertThat(StatusVistoriaPleno.DESCONHECIDO.statusImportacaoEmAndamento())
                .isEqualTo(PlenoImportacaoVistoria.Status.EM_ANDAMENTO);
    }
}
