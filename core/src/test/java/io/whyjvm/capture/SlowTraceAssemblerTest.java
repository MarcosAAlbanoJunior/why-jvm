package io.whyjvm.capture;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlowTraceAssemblerTest {

    private static final long MS = 1_000_000L;

    private static SpanNode span(String id, String parent, String name, long ms) {
        return new SpanNode(id, parent, name, ms * MS);
    }

    @Test
    void selfTimeDiscountsChildren() {
        // root 100ms com um filho de 70ms -> self do root = 30ms.
        List<SpanNode> spans = List.of(
                span("root", null, "POST /checkout", 100),
                span("c1", "root", "findById", 70));

        List<SlowTrace> out = SlowTraceAssembler.assemble(spans);

        SlowTrace root = out.stream().filter(s -> s.span().equals("POST /checkout")).findFirst().orElseThrow();
        SlowTrace child = out.stream().filter(s -> s.span().equals("findById")).findFirst().orElseThrow();
        assertEquals(100, root.totalMs());
        assertEquals(30, root.selfMs());
        assertEquals(70, child.selfMs());
    }

    @Test
    void ranksBySelfTimeAndCapsTopN() {
        List<SpanNode> spans = new ArrayList<>();
        spans.add(span("root", null, "GET /x", 500));
        for (int i = 0; i < 8; i++) {
            spans.add(span("s" + i, "root", "leaf-" + i, (i + 1) * 10L)); // 10..80ms
        }

        List<SlowTrace> out = SlowTraceAssembler.assemble(spans, 3);

        assertEquals(3, out.size());
        // o mais lento (root self = 500 - 360 = 140) primeiro, depois leaf-7 (80), leaf-6 (70).
        assertEquals("GET /x", out.get(0).span());
        assertEquals("leaf-7", out.get(1).span());
        assertEquals("leaf-6", out.get(2).span());
    }

    @Test
    void detectsNPlusOneFromRepeatedSiblings() {
        // 12 queries identicas de 5ms sob o mesmo pai: nenhuma isolada e lenta,
        // mas a repeticao (60ms) e a causa.
        List<SpanNode> spans = new ArrayList<>();
        spans.add(span("root", null, "GET /orders", 80));
        for (int i = 0; i < 12; i++) {
            spans.add(span("q" + i, "root", "SELECT orders", 5));
        }

        List<SlowTrace> out = SlowTraceAssembler.assemble(spans);

        SlowTrace n1 = out.stream().filter(s -> s.span().startsWith("N+1")).findFirst().orElseThrow();
        assertTrue(n1.span().contains("SELECT orders"), n1.span());
        assertTrue(n1.span().contains("×12"), n1.span());
        assertEquals(60, n1.totalMs());
        assertEquals(out.get(0), n1); // N+1 vem no topo
    }

    @Test
    void doesNotFlagBelowThreshold() {
        List<SpanNode> spans = new ArrayList<>();
        spans.add(span("root", null, "GET /x", 50));
        for (int i = 0; i < 4; i++) { // 4 < limiar 5
            spans.add(span("q" + i, "root", "SELECT x", 5));
        }
        List<SlowTrace> out = SlowTraceAssembler.assemble(spans);
        assertTrue(out.stream().noneMatch(s -> s.span().startsWith("N+1")), out.toString());
    }

    @Test
    void roundsSubMillisInsteadOfZeroing() {
        // filho de 500us deveria virar 1ms (arredonda), nao 0.
        List<SpanNode> spans = List.of(
                new SpanNode("root", null, "GET /x", 2 * MS),
                new SpanNode("c", "root", "fast", 500_000));
        List<SlowTrace> out = SlowTraceAssembler.assemble(spans);
        SlowTrace child = out.stream().filter(s -> s.span().equals("fast")).findFirst().orElseThrow();
        assertEquals(1, child.totalMs());
    }

    @Test
    void emptyOrNullReturnsEmpty() {
        assertTrue(SlowTraceAssembler.assemble(null).isEmpty());
        assertTrue(SlowTraceAssembler.assemble(List.of()).isEmpty());
    }
}
