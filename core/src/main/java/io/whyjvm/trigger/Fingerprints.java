package io.whyjvm.trigger;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;

/**
 * Computa a identidade de um incidente: {@code (endpoint, assinatura do erro)}.
 *
 * <p>A assinatura do erro e a classe da exception mais o topo do stack trace,
 * <b>normalizado</b> (sem numero de linha, que oscila a cada edicao do arquivo).
 * Duas falhas com a mesma assinatura no mesmo endpoint sao o mesmo incidente e,
 * portanto, uma unica investigacao — e ai que o {@link IncidentDeduplicator}
 * protege o orcamento de token.
 *
 * <p>Incidentes sem exception (ex.: {@code SLOW}) agrupam por endpoint + tipo.
 */
public final class Fingerprints {

    // Chaves semanticas do OTel para o evento de exception anexado ao span.
    private static final AttributeKey<String> EXC_TYPE = AttributeKey.stringKey("exception.type");
    private static final AttributeKey<String> EXC_STACK = AttributeKey.stringKey("exception.stacktrace");

    private Fingerprints() {
    }

    public static String of(String endpoint, IncidentType type, SpanData span) {
        String signature = errorSignature(span);
        if (signature == null) {
            // Sem exception (ex.: SLOW): a identidade e o endpoint + tipo.
            return endpoint + " | " + type;
        }
        return endpoint + " | " + signature;
    }

    private static String errorSignature(SpanData span) {
        for (EventData event : span.getEvents()) {
            if ("exception".equals(event.getName())) {
                String excType = event.getAttributes().get(EXC_TYPE);
                String topFrame = topFrame(event.getAttributes().get(EXC_STACK));
                if (excType == null && topFrame == null) {
                    return null;
                }
                return (excType != null ? excType : "?")
                        + (topFrame != null ? " @ " + topFrame : "");
            }
        }
        return null;
    }

    /**
     * Extrai e normaliza o topo do stack trace: a primeira linha {@code "at ..."},
     * reduzida ao metodo qualificado ({@code com.foo.Bar.metodo}) e <b>sem</b> o
     * numero de linha — assim a mesma falha mantem o mesmo fingerprint mesmo que
     * o arquivo seja editado e as linhas mudem.
     */
    static String topFrame(String stackTrace) {
        if (stackTrace == null || stackTrace.isBlank()) {
            return null;
        }
        for (String raw : stackTrace.split("\\R")) {
            String line = raw.strip();
            if (line.startsWith("at ")) {
                String frame = line.substring(3).strip();
                int paren = frame.indexOf('(');
                return paren >= 0 ? frame.substring(0, paren) : frame;
            }
        }
        return null;
    }
}
