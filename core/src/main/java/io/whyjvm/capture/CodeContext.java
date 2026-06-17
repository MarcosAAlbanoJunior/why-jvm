package io.whyjvm.capture;

/**
 * Contexto de codigo do frame de topo da app no stack da exception — o insumo do
 * Tier 2 (code-aware RCA): transformar <i>onde</i> (Arquivo:linha) em <i>por que</i>
 * mostrando o fonte do metodo suspeito.
 *
 * <p>Resolvido pelo agente in-JVM na captura (o unico componente com handle no
 * codigo da app monitorada — o servico de analise em Go opera sobre um snapshot
 * serializado e NUNCA acessa o fonte). O Go so renderiza o que recebe.
 *
 * <p>Princípio do projeto: nunca fabricar. Quando o fonte nao esta disponivel em
 * runtime (sem source jar no classpath nem diretorio configurado), {@code origin}
 * e {@link Origin#UNAVAILABLE} e {@code snippet} fica null — o laudo diz que nao
 * tem o fonte, em vez de inventar. Espelha o schema v1 ({@code codeContext}).
 */
public record CodeContext(
        String symbol,
        String file,
        int line,
        Origin origin,
        String snippet,
        int snippetStartLine
) {

    /** De onde o fonte foi (ou nao) resolvido. Serializa pelo nome no JSON do contrato. */
    public enum Origin {
        SOURCE_JAR,
        SOURCE_DIR,
        UNAVAILABLE
    }

    /** Frame resolvido, mas sem fonte disponivel — registra o simbolo, sem snippet. */
    public static CodeContext unavailable(String symbol, String file, int line) {
        return new CodeContext(symbol, file, line, Origin.UNAVAILABLE, null, 0);
    }

    /**
     * Bloco legivel para o laudo: cabecalho ({@code simbolo (Arquivo:linha, fonte)})
     * e, quando ha fonte, o trecho numerado com {@code >} marcando a linha do frame.
     * Sem fonte, vira uma linha honesta "fonte indisponivel em runtime".
     */
    public String render() {
        String head = symbol + " (" + file + ":" + line + ")";
        if (origin == Origin.UNAVAILABLE || snippet == null || snippet.isBlank()) {
            return head + " — fonte indisponivel em runtime";
        }
        StringBuilder sb = new StringBuilder(head).append(" — fonte: ").append(origin);
        int n = snippetStartLine;
        for (String row : snippet.split("\n", -1)) {
            sb.append('\n').append(String.format("%s%3d | %s", n == line ? ">" : " ", n, row));
            n++;
        }
        return sb.toString();
    }
}
