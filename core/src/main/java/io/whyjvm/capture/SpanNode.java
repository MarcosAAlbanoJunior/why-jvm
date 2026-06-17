package io.whyjvm.capture;

/**
 * O essencial de um span para reconstruir a arvore do trace: identidade, link com
 * o pai, nome e duracao. E o que o {@code TraceSpanBuffer} retem por span (barato)
 * e o que o {@link SlowTraceAssembler} consome para derivar os {@link SlowTrace}.
 *
 * <p>Duracao em nanos para nao perder spans sub-ms na soma (a conversao para ms,
 * com arredondamento, fica no assembler — onde entra no contrato).
 */
public record SpanNode(
        String spanId,
        String parentSpanId,
        String name,
        long durationNanos
) {
}
