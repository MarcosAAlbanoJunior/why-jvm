package io.whyjvm.llm;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import io.whyjvm.agent.LlmProvider;

import java.util.logging.Logger;

/**
 * Monta o {@link LlmProvider} real a partir do ambiente (BYOK). O caminho
 * multi-provider: o adapter ({@link LangChain4jProvider}) e unico; o que muda por
 * variavel e qual {@link ChatModel} do LangChain4j e construido.
 *
 * <pre>
 *   LLM_API_KEY   (obrigatoria; sem ela, cai no StubLlmProvider)
 *   LLM_PROVIDER  default "gemini"
 *   LLM_MODEL     default "gemini-2.5-flash"
 * </pre>
 */
public final class LlmProviders {

    private static final Logger LOG = Logger.getLogger(LlmProviders.class.getName());

    private LlmProviders() {
    }

    /** Provider do ambiente, ou {@code null} se nao houver key (chamador usa o stub). */
    public static LlmProvider fromEnv() {
        return create(System.getenv("LLM_PROVIDER"), System.getenv("LLM_API_KEY"), System.getenv("LLM_MODEL"));
    }

    /**
     * Monta o provider a partir de valores explicitos (de env var, arquivo de
     * propriedades, etc.), ou {@code null} se {@code apiKey} for vazia.
     */
    public static LlmProvider create(String provider, String apiKey, String model) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        String resolvedProvider = orDefault(provider, "gemini");
        String resolvedModel = orDefault(model, "gemini-2.5-flash");

        ChatModel chatModel = switch (resolvedProvider.toLowerCase()) {
            case "gemini" -> GoogleAiGeminiChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(resolvedModel)
                    .build();
            // Para Claude/OpenAI/Ollama: adicione a dependencia LangChain4j e um case aqui.
            default -> throw new IllegalArgumentException("Provider LLM nao suportado ainda: " + resolvedProvider);
        };

        LOG.info("Provider LLM ativo: " + resolvedProvider + " (" + resolvedModel + ")");
        return new LangChain4jProvider(resolvedProvider, chatModel);
    }

    private static String orDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
