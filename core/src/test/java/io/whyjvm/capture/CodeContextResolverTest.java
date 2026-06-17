package io.whyjvm.capture;

import io.whyjvm.trigger.IncidentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeContextResolverTest {

    private static final ClassLoader EMPTY = new URLClassLoader(new URL[0], null);
    private static final String REL = "io/whyjvm/sample/checkout/CustomerService.java";

    private static final String STACK = String.join("\n",
            "java.lang.NullPointerException: Cannot invoke \"Customer.tier()\" because \"customer\" is null",
            "\tat io.whyjvm.sample.checkout.CustomerService.calculateDiscount(CustomerService.java:3)",
            "\tat java.base/java.lang.reflect.Method.invoke(Method.java:568)");

    private static final String SOURCE = String.join("\n",
            "package io.whyjvm.sample.checkout;",       // 1
            "class CustomerService {",                  // 2
            "  Customer c = repo.findById(id);",        // 3 <- frame
            "}");                                        // 4

    private static IncidentRecord errorWith(String stack) {
        return IncidentRecord.initial(
                "inc", Instant.now(), "POST /checkout", IncidentType.ERROR, "fp", "http-nio-1", 42, 1,
                new ExceptionInfo("java.lang.NullPointerException", "customer is null", stack), null, null);
    }

    @Test
    void resolvesCodeContextForAppFrame(@TempDir Path dir) throws Exception {
        Path f = dir.resolve(REL);
        Files.createDirectories(f.getParent());
        Files.writeString(f, SOURCE, StandardCharsets.UTF_8);

        CodeContextResolver resolver = new CodeContextResolver(
                List.of("io.whyjvm.sample"), new SourceResolver(List.of(dir), EMPTY, 2));

        CodeContext cc = resolver.forIncident(errorWith(STACK));

        assertEquals("io.whyjvm.sample.checkout.CustomerService.calculateDiscount", cc.symbol());
        assertEquals(CodeContext.Origin.SOURCE_DIR, cc.origin());
        assertTrue(cc.snippet().contains("repo.findById(id)"), cc.snippet());
    }

    @Test
    void unavailableSourceStillReturnsContextWithSymbol() {
        // Sem diretorio/classpath com o fonte: anexa o simbolo, sem snippet (honesto).
        CodeContextResolver resolver = new CodeContextResolver(
                List.of("io.whyjvm.sample"), new SourceResolver(List.of(), EMPTY, 2));

        CodeContext cc = resolver.forIncident(errorWith(STACK));

        assertEquals(CodeContext.Origin.UNAVAILABLE, cc.origin());
        assertNull(cc.snippet());
        assertEquals("io.whyjvm.sample.checkout.CustomerService.calculateDiscount", cc.symbol());
    }

    @Test
    void nullWhenNoException() {
        IncidentRecord slow = IncidentRecord.initial(
                "inc", Instant.now(), "GET /x", IncidentType.SLOW, "fp", "t", 100, 1, null, null, null);
        CodeContextResolver resolver = new CodeContextResolver(List.of(), new SourceResolver(List.of(), EMPTY, 2));
        assertNull(resolver.forIncident(slow));
    }

    @Test
    void nullWhenNoStackTrace() {
        IncidentRecord noStack = IncidentRecord.initial(
                "inc", Instant.now(), "GET /x", IncidentType.ERROR, "fp", "t", 100, 1,
                new ExceptionInfo("java.lang.IllegalStateException", "boom", null), null, null);
        CodeContextResolver resolver = new CodeContextResolver(List.of(), new SourceResolver(List.of(), EMPTY, 2));
        assertNull(resolver.forIncident(noStack));
    }

    @Test
    void nullWhenNoAppFrameInStack() {
        String onlyInfra = "java.lang.RuntimeException\n\tat java.base/java.util.Objects.requireNonNull(Objects.java:233)";
        CodeContextResolver resolver = new CodeContextResolver(
                List.of("io.whyjvm.sample"), new SourceResolver(List.of(), EMPTY, 2));
        assertNull(resolver.forIncident(errorWith(onlyInfra)));
    }
}
