package io.whyjvm.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentLoopTest {

    @Test
    void extractJsonUnwrapsMarkdownFence() {
        String fenced = "```json\n{\"causa_raiz\":\"x\",\"confianca\":\"alta\"}\n```";
        assertEquals("{\"causa_raiz\":\"x\",\"confianca\":\"alta\"}", AgentLoop.extractJson(fenced));
    }

    @Test
    void extractJsonStripsSurroundingProse() {
        String prose = "Aqui esta o laudo:\n{\"tipo\":\"ERROR\"}\nEspero que ajude.";
        assertEquals("{\"tipo\":\"ERROR\"}", AgentLoop.extractJson(prose));
    }

    @Test
    void extractJsonReturnsRawWhenNoObject() {
        assertEquals("sem json aqui", AgentLoop.extractJson("sem json aqui"));
    }
}
