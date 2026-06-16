package io.whyjvm.capture;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.whyjvm.trigger.IncidentType;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fecha o loop produtor &lt;-&gt; contrato: serializa um {@link IncidentRecord}
 * com o mapper de producao e valida o JSON contra
 * {@code schema/incident-record.v1.json}. Se a forma do record divergir do schema
 * congelado no M0, este teste quebra.
 */
class IncidentRecordSchemaTest {

    private static final JsonSchema SCHEMA = loadSchema();

    @Test
    void fullSlowRecordMatchesSchema() throws Exception {
        IncidentRecord base = IncidentRecord.initial(
                "POST-checkout-d4e5f6", Instant.parse("2026-06-15T14:03:07.812Z"),
                "POST /checkout", IncidentType.SLOW, "POST /checkout|SLOW",
                "http-nio-8080-exec-3", 4200, 5,
                null, new Baseline(380.0, 5000, 3.0), new JvmContext(3640, 4096, "G1", 221));

        TriageSignals signals = new TriageSignals(3, 812, 1340, 0, 1288490188L);
        GcActivity gc = new GcActivity(3, 1340, List.of(
                new GcActivity.Pause("G1Old", "G1 Evacuation Pause", 812, 812)));
        AllocationHotspots alloc = new AllocationHotspots(1288490188L, 1842, List.of(
                new AllocationHotspots.Site("io.app.InvoiceBuilder.buildLineItems", 940572180L, 73.0)));
        ThreadActivity ta = new ThreadActivity("http-nio-8080-exec-3", 0, null, 0, 0, 0, 38);
        Dimensions dims = new Dimensions(gc, alloc, null, ta, null);

        IncidentRecord full = base.withEvidence(signals, dims, "file:incidents/checkout.jfr");

        assertValid(full);
    }

    @Test
    void minimalErrorRecordMatchesSchema() throws Exception {
        IncidentRecord err = IncidentRecord.initial(
                "POST-checkout-a1b2c3", Instant.parse("2026-06-15T14:03:07.812Z"),
                "POST /checkout", IncidentType.ERROR, "POST /checkout|NPE",
                "http-nio-8080-exec-7", 42, 1,
                new ExceptionInfo("java.lang.NullPointerException", "cart is null",
                        "java.lang.NullPointerException\n\tat io.app.InvoiceBuilder.buildLineItems(InvoiceBuilder.java:48)"),
                null, null);

        assertValid(err);
    }

    @Test
    void errorRecordWithCodeContextMatchesSchema() throws Exception {
        IncidentRecord err = IncidentRecord.initial(
                "POST-checkout-a1b2c3", Instant.parse("2026-06-15T14:03:07.812Z"),
                "POST /checkout", IncidentType.ERROR, "POST /checkout|NPE",
                "http-nio-8080-exec-7", 42, 1,
                new ExceptionInfo("java.lang.NullPointerException", "Cannot invoke \"...tier()\"",
                        "java.lang.NullPointerException\n\tat io.app.checkout.CustomerService.calculateDiscount(CustomerService.java:20)"),
                null, null);

        IncidentRecord withCode = err.withCodeContext(new CodeContext(
                "io.app.checkout.CustomerService.calculateDiscount", "CustomerService.java", 20,
                CodeContext.Origin.SOURCE_DIR,
                "    public double calculateDiscount(String customerId) {\n"
                        + "        Customer customer = repository.findById(customerId);\n"
                        + "        return switch (customer.tier()) {",
                18));

        assertValid(withCode);
    }

    @Test
    void errorRecordWithUnavailableCodeContextMatchesSchema() throws Exception {
        IncidentRecord err = IncidentRecord.initial(
                "POST-checkout-a1b2c3", Instant.parse("2026-06-15T14:03:07.812Z"),
                "POST /checkout", IncidentType.ERROR, "POST /checkout|NPE",
                "http-nio-8080-exec-7", 42, 1, null, null, null);

        IncidentRecord withCode = err.withCodeContext(CodeContext.unavailable(
                "io.app.checkout.CustomerService.calculateDiscount", "CustomerService.java", 20));

        assertValid(withCode);
    }

    private static void assertValid(IncidentRecord record) throws Exception {
        JsonNode node = IncidentRecordJson.mapper().readTree(IncidentRecordJson.toJson(record));
        Set<ValidationMessage> errors = SCHEMA.validate(node);
        assertTrue(errors.isEmpty(), () -> "JSON invalido frente ao schema v1: " + errors);
    }

    private static JsonSchema loadSchema() {
        Path schemaPath = locateSchema();
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        try (InputStream in = Files.newInputStream(schemaPath)) {
            return factory.getSchema(in);
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao carregar o schema em " + schemaPath, e);
        }
    }

    /** Procura schema/incident-record.v1.json subindo a partir do working dir do teste. */
    private static Path locateSchema() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve("schema").resolve("incident-record.v1.json");
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("schema/incident-record.v1.json nao encontrado a partir de "
                + Path.of("").toAbsolutePath());
    }
}
