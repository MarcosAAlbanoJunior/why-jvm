package io.whyjvm.capture;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Monta a dimensao {@code slowTraces} a partir dos spans retidos de um trace —
 * o insumo do Tier 3 (get_slow_traces): <i>quem dominou a latencia</i> e <i>N+1</i>.
 * Fold puro (sem OTel), no estilo dos folds de {@link EvidenceExtractor}.
 *
 * <p>Dois sinais, ambos no shape {@link SlowTrace} (sem mudar o contrato):
 * <ul>
 *   <li><b>% do tempo</b>: top-N spans por <i>self time</i> ({@code total − Σ filhos
 *       diretos}) — separa o tempo gasto no proprio span do tempo nos downstream.</li>
 *   <li><b>N+1</b>: spans irmaos (mesmo pai) repetidos pelo nome acima de um limiar
 *       viram uma entrada sintetica {@code "N+1: <nome> ×N"} com o tempo somado — o
 *       caso classico em que nenhum span isolado e lento, mas a repeticao e a causa.</li>
 * </ul>
 */
public final class SlowTraceAssembler {

    static final int TOP_N = 5;
    /** A partir de quantos irmaos identicos a repeticao e tratada como N+1. */
    static final int N_PLUS_ONE_THRESHOLD = 5;

    private SlowTraceAssembler() {
    }

    public static List<SlowTrace> assemble(List<SpanNode> spans) {
        return assemble(spans, TOP_N);
    }

    public static List<SlowTrace> assemble(List<SpanNode> spans, int topN) {
        if (spans == null || spans.isEmpty()) {
            return List.of();
        }
        Map<String, List<SpanNode>> childrenOf = new LinkedHashMap<>();
        for (SpanNode s : spans) {
            if (s.parentSpanId() != null) {
                childrenOf.computeIfAbsent(s.parentSpanId(), k -> new ArrayList<>()).add(s);
            }
        }

        List<SlowTrace> out = new ArrayList<>(nPlusOne(childrenOf));
        out.addAll(topBySelfTime(spans, childrenOf, topN));
        return List.copyOf(out);
    }

    /** Entradas sinteticas para grupos de irmaos repetidos (>= limiar), por tempo somado. */
    private static List<SlowTrace> nPlusOne(Map<String, List<SpanNode>> childrenOf) {
        List<SlowTrace> repeated = new ArrayList<>();
        for (List<SpanNode> siblings : childrenOf.values()) {
            Map<String, long[]> byName = new LinkedHashMap<>(); // nome -> [count, sumNanos]
            for (SpanNode c : siblings) {
                long[] agg = byName.computeIfAbsent(c.name(), k -> new long[2]);
                agg[0]++;
                agg[1] += c.durationNanos();
            }
            byName.forEach((name, agg) -> {
                if (agg[0] >= N_PLUS_ONE_THRESHOLD) {
                    long sumMs = nanosToMs(agg[1]);
                    repeated.add(new SlowTrace("N+1: " + name + " ×" + agg[0], sumMs, sumMs));
                }
            });
        }
        repeated.sort(Comparator.comparingLong(SlowTrace::totalMs).reversed());
        return repeated;
    }

    /** Top-N spans por self time (total menos a soma dos filhos diretos). */
    private static List<SlowTrace> topBySelfTime(
            List<SpanNode> spans, Map<String, List<SpanNode>> childrenOf, int topN) {
        return spans.stream()
                .map(s -> {
                    long childNanos = childrenOf.getOrDefault(s.spanId(), List.of()).stream()
                            .mapToLong(SpanNode::durationNanos).sum();
                    long selfNanos = Math.max(0, s.durationNanos() - childNanos);
                    return new SlowTrace(s.name(), nanosToMs(selfNanos), nanosToMs(s.durationNanos()));
                })
                .sorted(Comparator.comparingLong(SlowTrace::selfMs).reversed())
                .limit(topN)
                .toList();
    }

    private static long nanosToMs(long nanos) {
        return (nanos + 500_000) / 1_000_000; // arredonda para nao zerar spans sub-ms
    }
}
