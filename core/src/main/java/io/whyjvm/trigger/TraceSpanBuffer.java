package io.whyjvm.trigger;

import io.whyjvm.capture.SpanNode;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Retem os spans por trace conforme terminam, para o gatilho montar a arvore no
 * disparo — os filhos fecham antes do root, entao precisam ser guardados
 * especulativamente (no {@code onEnd} nao se sabe ainda se o trace vira incidente).
 *
 * <p>Sempre ligado, mas <b>bounded</b>: limita traces em voo, spans por trace e a
 * idade (TTL, para traces cujo root nunca fecha). Evicta em tres pontos: ao coletar
 * (disparo), quando o root fecha sem incidente, e por cap/TTL na entrada de um trace
 * novo. Estrutura concorrente: o {@code onEnd} roda na thread de cada request.
 */
public final class TraceSpanBuffer {

    static final int MAX_TRACES = 2048;
    static final int MAX_SPANS_PER_TRACE = 512;
    static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

    private final int maxTraces;
    private final int maxSpansPerTrace;
    private final long ttlNanos;
    private final Map<String, Entry> traces = new ConcurrentHashMap<>();

    public TraceSpanBuffer() {
        this(MAX_TRACES, MAX_SPANS_PER_TRACE, DEFAULT_TTL);
    }

    public TraceSpanBuffer(int maxTraces, int maxSpansPerTrace, Duration ttl) {
        this.maxTraces = maxTraces;
        this.maxSpansPerTrace = maxSpansPerTrace;
        this.ttlNanos = ttl.toNanos();
    }

    /** Registra um span que terminou. Barato; chamado em todo {@code onEnd}. */
    public void record(String traceId, String spanId, String parentSpanId, String name, long durationNanos) {
        if (traceId == null) {
            return;
        }
        Entry e = traces.computeIfAbsent(traceId, k -> {
            evictIfNeeded();
            return new Entry();
        });
        if (e.count.get() < maxSpansPerTrace) {
            e.spans.add(new SpanNode(spanId, parentSpanId, name, durationNanos));
            e.count.incrementAndGet();
        }
    }

    /** Spans do trace, removendo-o do buffer (chamado no disparo). */
    public List<SpanNode> collectAndEvict(String traceId) {
        Entry e = traceId == null ? null : traces.remove(traceId);
        return e == null ? List.of() : List.copyOf(e.spans);
    }

    /** Descarta o trace sem coletar (root fechou e nao virou incidente). */
    public void evict(String traceId) {
        if (traceId != null) {
            traces.remove(traceId);
        }
    }

    int size() {
        return traces.size();
    }

    /** No cap: limpa expirados; se ainda cheio, descarta o trace mais antigo. */
    private void evictIfNeeded() {
        if (traces.size() < maxTraces) {
            return;
        }
        long now = System.nanoTime();
        traces.values().removeIf(e -> now - e.createdNanos > ttlNanos);
        if (traces.size() >= maxTraces) {
            traces.entrySet().stream()
                    .min(Comparator.comparingLong(en -> en.getValue().createdNanos))
                    .map(Map.Entry::getKey)
                    .ifPresent(traces::remove);
        }
    }

    private static final class Entry {
        final Queue<SpanNode> spans = new ConcurrentLinkedQueue<>();
        final AtomicInteger count = new AtomicInteger();
        final long createdNanos = System.nanoTime();
    }
}
