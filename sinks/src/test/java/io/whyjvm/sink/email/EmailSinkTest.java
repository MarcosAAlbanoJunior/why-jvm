package io.whyjvm.sink.email;

import io.whyjvm.agent.Laudo;
import io.whyjvm.capture.CodeContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testa a renderizacao do e-mail isolada do envio SMTP (sem rede).
 */
class EmailSinkTest {

    private static final Laudo LAUDO = new Laudo(
            "POST /checkout", "SLOW",
            "Pausa de GC full disparada por alocacao em buildLineItems",
            List.of("Pausa de GC de 812ms", "buildLineItems = 73% das alocacoes"),
            "alta",
            "Reutilizar um buffer elimina a alocacao quadratica",
            List.of("Contencao de lock / deadlock: sem espera relevante em monitor"),
            null);

    private static final Laudo LAUDO_COM_CODIGO = new Laudo(
            "POST /checkout", "ERROR",
            "NPE: findById retornou null e calculateDiscount nao validou",
            List.of("customer is null"),
            "alta",
            "Validar o retorno de findById antes de acessar tier()",
            List.of(),
            new CodeContext(
                    "io.whyjvm.sample.checkout.CustomerService.calculateDiscount", "CustomerService.java", 20,
                    CodeContext.Origin.SOURCE_DIR, "return switch (customer.tier()) {", 20));

    @Test
    void subjectSummarizesIncident() {
        assertEquals("[why-jvm] RCA SLOW em POST /checkout (confianca alta)",
                EmailSink.renderSubject(LAUDO));
    }

    @Test
    void bodyCarriesCauseEvidenceAndFix() {
        String body = EmailSink.renderBody(LAUDO);

        assertTrue(body.contains("Causa raiz: Pausa de GC full"), body);
        assertTrue(body.contains("  - Pausa de GC de 812ms"), body);
        assertTrue(body.contains("  - buildLineItems = 73% das alocacoes"), body);
        assertTrue(body.contains("Hipoteses descartadas:"), body);
        assertTrue(body.contains("  + Contencao de lock / deadlock"), body);
        assertTrue(body.contains("Reutilizar um buffer"), body);
    }

    @Test
    void bodyOmitsCodeSectionWhenNoCodeContext() {
        // LAUDO (SLOW, sem codeContext) nao deve trazer a secao de codigo.
        assertTrue(!EmailSink.renderBody(LAUDO).contains("Codigo do metodo suspeito"),
                EmailSink.renderBody(LAUDO));
    }

    @Test
    void bodyCarriesSuspectMethodSourceWhenPresent() {
        String body = EmailSink.renderBody(LAUDO_COM_CODIGO);

        assertTrue(body.contains("Codigo do metodo suspeito:"), body);
        assertTrue(body.contains("CustomerService.java:20"), body);
        assertTrue(body.contains("> 20 | return switch (customer.tier()) {"), body);
    }

    @Test
    void bodyHandlesEmptyEvidence() {
        Laudo semEvidencia = new Laudo("GET /x", "ERROR", "NPE", List.of(), "baixa", "", List.of(), null);
        assertTrue(EmailSink.renderBody(semEvidencia).contains("(sem evidencia listada)"));
    }
}
