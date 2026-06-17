package io.whyjvm.sample.checkout;

import io.whyjvm.agent.AgentLoop;
import io.whyjvm.agent.Laudo;
import io.whyjvm.agent.StubLlmProvider;
import io.whyjvm.capture.CodeContext;
import io.whyjvm.capture.CodeContextResolver;
import io.whyjvm.capture.EvidenceCapture;
import io.whyjvm.capture.ExceptionInfo;
import io.whyjvm.capture.IncidentRecord;
import io.whyjvm.capture.IncidentStore;
import io.whyjvm.capture.InMemoryIncidentStore;
import io.whyjvm.capture.JfrEvidenceCapture;
import io.whyjvm.capture.SourceResolver;
import io.whyjvm.mcp.McpToolRegistry;
import io.whyjvm.mcp.tools.GetExceptionDetailsTool;
import io.whyjvm.trigger.IncidentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Aceitacao ponta a ponta do Tier 2 (code-aware RCA) sobre o cenario real do
 * {@code checkout}: dispara o NPE de verdade em {@link CustomerService}, leva o
 * stack pela captura ({@link JfrEvidenceCapture#extract}) com o fonte do
 * sample-app, e verifica que o laudo nomeia o metodo suspeito E mostra o trecho
 * do fonte com a linha do bug. Tudo deterministico: sem JFR/LLM/rede — esses sao
 * ortogonais ao code-aware (o snapshot nao entra; o LLM so narra).
 */
class CodeAwareRcaAcceptanceTest {

    @Test
    void laudoNamesSuspectMethodAndShowsRealSource(@TempDir Path incidentDir) {
        // 1) O bug real do checkout: cliente inexistente -> findById null -> NPE em tier().
        String stack = realCheckoutNpeStack();
        assertTrue(stack.contains("io.whyjvm.sample.checkout.CustomerService.calculateDiscount"), stack);

        IncidentStore store = new InMemoryIncidentStore();
        IncidentRecord record = IncidentRecord.initial(
                "checkout-npe", Instant.now(), "POST /checkout", IncidentType.ERROR,
                "POST /checkout|NPE", "http-nio-1", 42, 1,
                new ExceptionInfo("java.lang.NullPointerException", "customer is null", stack),
                null, null);
        store.save(record);

        // 2) Pipeline Tier 2 de verdade: resolve o fonte do frame de topo da app a
        //    partir da raiz de fonte do sample-app (SOURCE_DIR), na captura.
        CodeContextResolver resolver = new CodeContextResolver(
                List.of("io.whyjvm.sample"),
                new SourceResolver(List.of(sampleSourceRoot()), null, 6));
        JfrEvidenceCapture capture = new JfrEvidenceCapture(incidentDir, store, resolver);

        IncidentRecord enriched = capture.extract(new EvidenceCapture.Captured(record, null));

        CodeContext cc = enriched.codeContext();
        assertNotNull(cc, "esperava code context resolvido");
        assertTrue(cc.symbol().endsWith("CustomerService.calculateDiscount"), cc.symbol());
        assertEquals(CodeContext.Origin.SOURCE_DIR, cc.origin());
        assertEquals("CustomerService.java", cc.file());
        assertTrue(cc.snippet().contains("customer.tier()"), cc.snippet());

        // 3) O laudo carrega o code context e o renderiza (o mesmo bloco que os
        //    sinks embutem): nomeia o metodo suspeito E mostra a linha do bug.
        McpToolRegistry registry = new McpToolRegistry().register(new GetExceptionDetailsTool(store));
        Laudo laudo = new AgentLoop(new StubLlmProvider(), registry).investigate(enriched);

        assertNotNull(laudo.codeContext(), "o laudo deveria carregar o code context");
        String rendered = laudo.codeContext().render();
        assertTrue(rendered.contains("CustomerService.java"), rendered);
        assertTrue(rendered.contains("calculateDiscount"), rendered);
        assertTrue(rendered.contains("customer.tier()"), rendered);
        assertTrue(rendered.contains("fonte: SOURCE_DIR"), rendered);
    }

    /** Executa o caminho do checkout com cliente inexistente e captura o stack do NPE real. */
    private static String realCheckoutNpeStack() {
        OrderService order = new OrderService(new CustomerService(new CustomerRepository()));
        try {
            order.totalWithDiscount("cliente-inexistente", 100.0);
            return fail("esperava NullPointerException do cenario checkout");
        } catch (NullPointerException e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            return sw.toString();
        }
    }

    /** Localiza sample-app/src/main/java subindo a partir do working dir do teste. */
    private static Path sampleSourceRoot() {
        Path direct = Path.of("src/main/java");
        if (Files.isDirectory(direct.resolve("io/whyjvm/sample/checkout"))) {
            return direct.toAbsolutePath();
        }
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve("sample-app").resolve("src/main/java");
            if (Files.isDirectory(candidate.resolve("io/whyjvm/sample/checkout"))) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("raiz de fonte do sample-app nao encontrada a partir de "
                + Path.of("").toAbsolutePath());
    }
}
