package io.whyjvm.capture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeContextTest {

    @Test
    void renderNumbersLinesAndMarksTheFrameLine() {
        String snippet = String.join("\n",
                "Customer customer = repository.findById(customerId);",
                "return switch (customer.tier()) {",
                "  case \"GOLD\" -> 0.20;");
        CodeContext cc = new CodeContext(
                "io.app.checkout.CustomerService.calculateDiscount", "CustomerService.java", 20,
                CodeContext.Origin.SOURCE_DIR, snippet, 19);

        String out = cc.render();

        assertTrue(out.contains("CustomerService.java:20"), out);
        assertTrue(out.contains("fonte: SOURCE_DIR"), out);
        assertTrue(out.contains("  19 | Customer customer = repository.findById(customerId);"), out);
        assertTrue(out.contains("> 20 | return switch (customer.tier()) {"), out); // marcador na linha do frame
        assertTrue(out.contains("  21 |   case \"GOLD\" -> 0.20;"), out);
    }

    @Test
    void renderUnavailableIsAnHonestOneLiner() {
        CodeContext cc = CodeContext.unavailable(
                "io.app.checkout.CustomerService.calculateDiscount", "CustomerService.java", 20);

        assertEquals(
                "io.app.checkout.CustomerService.calculateDiscount (CustomerService.java:20)"
                        + " — fonte indisponivel em runtime",
                cc.render());
    }
}
