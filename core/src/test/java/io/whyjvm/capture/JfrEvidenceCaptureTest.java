package io.whyjvm.capture;

import io.whyjvm.capture.EvidenceCapture.Captured;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class JfrEvidenceCaptureTest {

    private static final ClassLoader EMPTY = new URLClassLoader(new URL[0], null);
    private static final String REL = "io/whyjvm/sample/checkout/CustomerService.java";

    private static final String STACK = String.join("\n",
            "java.lang.NullPointerException",
            "\tat io.whyjvm.sample.checkout.CustomerService.calculateDiscount(CustomerService.java:3)");

    private static final String SOURCE = String.join("\n",
            "package io.whyjvm.sample.checkout;",
            "class CustomerService {",
            "  Customer c = repo.findById(id);",
            "}");

    private static IncidentRecord errorRecord() {
        return IncidentRecord.initial(
                "inc", Instant.now(), "POST /checkout", IncidentType.ERROR, "fp", "http-nio-1", 42, 1,
                new ExceptionInfo("java.lang.NullPointerException", "customer is null", STACK), null, null);
    }

    @Test
    void extractAttachesCodeContextEvenWithoutJfr(@TempDir Path dir) throws Exception {
        Path srcRoot = dir.resolve("src");
        Path f = srcRoot.resolve(REL);
        Files.createDirectories(f.getParent());
        Files.writeString(f, SOURCE, StandardCharsets.UTF_8);

        IncidentStore store = new InMemoryIncidentStore();
        CodeContextResolver resolver = new CodeContextResolver(
                List.of("io.whyjvm.sample"), new SourceResolver(List.of(srcRoot), EMPTY, 2));
        JfrEvidenceCapture capture = new JfrEvidenceCapture(dir.resolve("incidents"), store, resolver);

        IncidentRecord record = errorRecord();
        store.save(record);

        IncidentRecord out = capture.extract(new Captured(record, null));

        assertNotNull(out.codeContext());
        assertEquals(CodeContext.Origin.SOURCE_DIR, out.codeContext().origin());
        // persistiu o enriquecimento no store
        assertEquals(CodeContext.Origin.SOURCE_DIR,
                store.find("inc").orElseThrow().codeContext().origin());
    }

    @Test
    void extractWithoutResolverLeavesCodeContextNull(@TempDir Path dir) {
        IncidentStore store = new InMemoryIncidentStore();
        JfrEvidenceCapture capture = new JfrEvidenceCapture(dir.resolve("incidents"), store);

        IncidentRecord out = capture.extract(new Captured(errorRecord(), null));

        assertNull(out.codeContext());
    }
}
