package br.com.vistoria.worker.pleno;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Gerador de dados fictícios (estilo 4devs) para a pessoa/cliente que a Pleno
 * exige antes de criar uma imobiliária. NÃO é um cadastro de negócio — só existe
 * para satisfazer o `pes_codigo` do `cliente/store`. Persistido em
 * `pleno_pessoa_garantia` para rastreabilidade.
 *
 * @param nome     nome completo plausível
 * @param cpf      11 dígitos, DVs válidos (mód-11)
 * @param email    sempre {@code @nao-responder.inspecta.local} — não recebe nada
 * @param telefone {@code (DD) 9XXXX-XXXX}
 */
public record DadosFalsos(String nome, String cpf, String email, String telefone) {

    private static final List<String> PRIMEIROS = List.of(
            "Ana", "Bruno", "Carla", "Diego", "Elaine", "Felipe", "Gabriela", "Henrique",
            "Isabela", "João", "Larissa", "Marcelo", "Natália", "Otávio", "Paula", "Rafael",
            "Sofia", "Thiago", "Vanessa", "William");
    private static final List<String> SOBRENOMES = List.of(
            "Silva", "Souza", "Oliveira", "Santos", "Pereira", "Lima", "Carvalho", "Ferreira",
            "Rodrigues", "Almeida", "Nascimento", "Araújo", "Ribeiro", "Barbosa", "Gomes");
    private static final int[] DDDS = {11, 21, 31, 41, 51, 61, 62, 71, 81, 85};

    public static DadosFalsos gerar() {
        return gerar(ThreadLocalRandom.current());
    }

    /** Determinístico quando recebe um RNG semeado — usado em teste. */
    public static DadosFalsos gerar(java.util.Random r) {
        String primeiro = PRIMEIROS.get(r.nextInt(PRIMEIROS.size()));
        String sobrenome = SOBRENOMES.get(r.nextInt(SOBRENOMES.size()));
        String nome = primeiro + " " + sobrenome;

        String cpf = cpfValido(r);

        String slug = java.text.Normalizer.normalize(nome, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase().replace(' ', '.');
        String email = slug + "." + Integer.toHexString(r.nextInt(0x10000)) + "@nao-responder.inspecta.local";

        return new DadosFalsos(nome, cpf, email, telefoneFalso(r));
    }

    /**
     * Telefone fictício {@code (DD) 9XXXX-XXXX} avulso — usado quando a Pleno exige
     * {@code pes_telefone} num {@code pessoa/store} e o registro Inspecta (usuário/interveniente)
     * não tem telefone cadastrado. Como o resto de {@link DadosFalsos}, não recebe nada.
     * (Nome não pode ser {@code telefone()} — colide com o accessor do record.)
     */
    public static String telefoneFalso() {
        return telefoneFalso(ThreadLocalRandom.current());
    }

    /** Determinístico quando recebe um RNG semeado — usado em teste. */
    public static String telefoneFalso(java.util.Random r) {
        int ddd = DDDS[r.nextInt(DDDS.length)];
        return String.format("(%02d) 9%04d-%04d", ddd, r.nextInt(10000), r.nextInt(10000));
    }

    private static String cpfValido(java.util.Random r) {
        int[] n = new int[11];
        for (int i = 0; i < 9; i++) n[i] = r.nextInt(10);
        n[9] = dv(n, 9, 10);
        n[10] = dv(n, 10, 11);
        StringBuilder sb = new StringBuilder(11);
        for (int d : n) sb.append(d);
        return sb.toString();
    }

    private static int dv(int[] n, int len, int pesoInicial) {
        int soma = 0;
        for (int i = 0; i < len; i++) soma += n[i] * (pesoInicial - i);
        int resto = soma % 11;
        return resto < 2 ? 0 : 11 - resto;
    }
}
