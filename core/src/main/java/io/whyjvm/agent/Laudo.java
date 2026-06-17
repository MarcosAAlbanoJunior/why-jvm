package io.whyjvm.agent;

import io.whyjvm.capture.CodeContext;

import java.util.List;

/**
 * Saida estruturada do agente. Formato fixo para poder rotear, armazenar e
 * auditar. A correlacao numerica fica na triagem deterministica, nao aqui: o
 * agente narra e prioriza, nao calcula. Por isso confianca e evidencia sao
 * sempre exigidas.
 */
public record Laudo(
        String endpoint,
        String tipo,
        String causaRaiz,
        List<String> evidencia,
        String confianca,
        String correcaoSugerida,
        List<String> hipotesesDescartadas,
        CodeContext codeContext
) {
}
