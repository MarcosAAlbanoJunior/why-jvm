package io.whyjvm.sink.email;

import io.whyjvm.agent.Laudo;
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
            "Reutilizar um buffer elimina a alocacao quadratica");

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
        assertTrue(body.contains("Reutilizar um buffer"), body);
    }

    @Test
    void bodyHandlesEmptyEvidence() {
        Laudo semEvidencia = new Laudo("GET /x", "ERROR", "NPE", List.of(), "baixa", "");
        assertTrue(EmailSink.renderBody(semEvidencia).contains("(sem evidencia listada)"));
    }
}
