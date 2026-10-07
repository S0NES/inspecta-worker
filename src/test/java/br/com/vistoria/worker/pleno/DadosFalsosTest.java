package br.com.vistoria.worker.pleno;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DadosFalsos")
class DadosFalsosTest {

    @Test
    @DisplayName("CPF tem 11 dígitos com DVs válidos (mód-11)")
    void cpfValido() {
        for (long seed = 0; seed < 50; seed++) {
            String cpf = DadosFalsos.gerar(new Random(seed)).cpf();
            assertThat(cpf).matches("\\d{11}");
            assertThat(dvOk(cpf)).as("CPF %s", cpf).isTrue();
        }
    }

    @Test
    @DisplayName("email sempre no domínio nao-responder e telefone no formato (DD) 9XXXX-XXXX")
    void formatos() {
        DadosFalsos d = DadosFalsos.gerar(new Random(1));
        assertThat(d.email()).endsWith("@nao-responder.inspecta.local");
        assertThat(d.telefone()).matches("\\(\\d{2}\\) 9\\d{4}-\\d{4}");
        assertThat(d.nome()).contains(" ");
    }

    @Test
    @DisplayName("determinístico com RNG semeado")
    void determinismo() {
        assertThat(DadosFalsos.gerar(new Random(42))).isEqualTo(DadosFalsos.gerar(new Random(42)));
    }

    private static boolean dvOk(String cpf) {
        int[] n = cpf.chars().map(c -> c - '0').toArray();
        return n[9] == dv(n, 9, 10) && n[10] == dv(n, 10, 11);
    }

    private static int dv(int[] n, int len, int peso) {
        int soma = 0;
        for (int i = 0; i < len; i++) soma += n[i] * (peso - i);
        int r = soma % 11;
        return r < 2 ? 0 : 11 - r;
    }
}
