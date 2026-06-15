package io.whyjvm.trigger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FingerprintsTest {

    @Test
    void topFrameKeepsQualifiedMethodWithoutLineNumber() {
        String stack = """
                java.lang.IllegalStateException: boom
                \tat com.foo.Bar.doIt(Bar.java:42)
                \tat com.foo.App.main(App.java:7)
                """;

        // O numero de linha (42) e descartado: editar o arquivo nao muda o fingerprint.
        assertEquals("com.foo.Bar.doIt", Fingerprints.topFrame(stack));
    }

    @Test
    void topFrameNullWhenNoStackFrames() {
        assertNull(Fingerprints.topFrame("apenas uma mensagem, sem frames"));
        assertNull(Fingerprints.topFrame(""));
        assertNull(Fingerprints.topFrame(null));
    }

    @Test
    void topFrameHandlesNativeFrameWithoutParenthesis() {
        String stack = "java.lang.Exception\n\tat com.foo.Native.call\n";
        assertEquals("com.foo.Native.call", Fingerprints.topFrame(stack));
    }
}
