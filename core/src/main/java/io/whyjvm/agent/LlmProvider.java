package io.whyjvm.agent;

import io.whyjvm.mcp.Tool;

import java.util.List;

/**
 * Fronteira de extensao da IA (ponto 1 do modo autonomo: aceitar varios tipos
 * de IA). O loop e identico para Claude, Gemini ou OpenAI; cada provider so
 * traduz o contexto e as tools para o seu formato de function calling.
 *
 * <p>BYOK: a implementacao le a propria key do ambiente. O nucleo nunca guarda
 * credencial.
 */
public interface LlmProvider {

    /** Nome do provider, para log e config (ex: "claude", "gemini", "stub"). */
    String name();

    /** Um turno: recebe o contexto e o catalogo de tools, devolve a decisao. */
    LlmResponse generate(List<Message> context, List<Tool> tools);
}
