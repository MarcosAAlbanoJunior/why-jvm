package io.whyjvm.mcp;

import java.util.Map;

/**
 * Uma ferramenta de diagnostico exposta ao agente. Cada tool e o Portao 2 em
 * acao: le o pacote de evidencia e devolve um <b>agregado</b>, nunca eventos
 * crus. Audite o tamanho de cada retorno: e onde o custo de token escapa.
 *
 * <p>No v1 as tools sao chamadas em processo. Na Fase 5 cada uma vira uma
 * {@code SyncToolSpecification} do SDK MCP exposta por transporte HTTP, sem
 * mudar a logica de agregacao aqui dentro.
 */
public interface Tool {

    /** Nome estavel usado pelo modelo no function calling (ex: "triage"). */
    String name();

    /** Descricao curta que o modelo le para decidir quando chamar. */
    String description();

    /** JSON Schema dos parametros de entrada. */
    Map<String, Object> inputSchema();

    /** Executa a tool e devolve o agregado pronto para o contexto do agente. */
    ToolResult execute(Map<String, Object> arguments);
}
