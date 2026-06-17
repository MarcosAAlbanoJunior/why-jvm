package io.whyjvm.capture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceResolverTest {

    private static final String REL = "io/whyjvm/sample/checkout/CustomerService.java";
    private static final AppFrame FRAME = new AppFrame(
            "io.whyjvm.sample.checkout.CustomerService.calculateDiscount", "CustomerService.java", 4);

    /** ClassLoader sem nada no classpath — forca a cadeia a pular o passo SOURCE_JAR. */
    private static final ClassLoader EMPTY = new URLClassLoader(new URL[0], null);

    private static Path writeSource(Path root, String rel, String body) throws Exception {
        Path f = root.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, body, StandardCharsets.UTF_8);
        return f;
    }

    private static final String SOURCE = String.join("\n",
            "package io.whyjvm.sample.checkout;",          // 1
            "public class CustomerService {",              // 2
            "  double calc(String id) {",                  // 3
            "    Customer c = repo.findById(id);",         // 4  <- linha do frame
            "    return switch (c.tier()) {",              // 5
            "      case \"GOLD\" -> 0.20;",                 // 6
            "      default -> 0.0; };",                     // 7
            "  }",                                          // 8
            "}");                                           // 9

    @Test
    void resolvesFromConfiguredDir(@TempDir Path dir) throws Exception {
        writeSource(dir, REL, SOURCE);
        SourceResolver resolver = new SourceResolver(List.of(dir), EMPTY, 2);

        CodeContext cc = resolver.resolve(FRAME);

        assertEquals(CodeContext.Origin.SOURCE_DIR, cc.origin());
        assertEquals(4, cc.line());
        assertEquals(2, cc.snippetStartLine()); // line 4 - 2 de contexto
        assertTrue(cc.snippet().contains("repo.findById(id)"), cc.snippet());
        assertTrue(cc.snippet().contains("public class CustomerService"), cc.snippet());
        assertTrue(cc.snippet().contains("c.tier()"), cc.snippet());
    }

    @Test
    void resolvesFromClasspathAsSourceJar(@TempDir Path cp) throws Exception {
        writeSource(cp, REL, SOURCE);
        ClassLoader withSource = new URLClassLoader(new URL[]{cp.toUri().toURL()}, null);
        SourceResolver resolver = new SourceResolver(List.of(), withSource, 2);

        CodeContext cc = resolver.resolve(FRAME);

        assertEquals(CodeContext.Origin.SOURCE_JAR, cc.origin());
        assertTrue(cc.snippet().contains("repo.findById(id)"), cc.snippet());
    }

    @Test
    void classpathTakesPrecedenceOverDir(@TempDir Path cp, @TempDir Path dir) throws Exception {
        writeSource(cp, REL, SOURCE);
        writeSource(dir, REL, "// versao do diretorio\n".repeat(10));
        ClassLoader withSource = new URLClassLoader(new URL[]{cp.toUri().toURL()}, null);
        SourceResolver resolver = new SourceResolver(List.of(dir), withSource, 2);

        assertEquals(CodeContext.Origin.SOURCE_JAR, resolver.resolve(FRAME).origin());
    }

    @Test
    void unavailableWhenSourceNowhere(@TempDir Path emptyDir) {
        SourceResolver resolver = new SourceResolver(List.of(emptyDir), EMPTY, 2);

        CodeContext cc = resolver.resolve(FRAME);

        assertEquals(CodeContext.Origin.UNAVAILABLE, cc.origin());
        assertNull(cc.snippet());
        assertEquals(0, cc.snippetStartLine());
        assertEquals(FRAME.symbol(), cc.symbol());
    }

    @Test
    void windowClampsAtFileStart(@TempDir Path dir) throws Exception {
        writeSource(dir, REL, SOURCE);
        AppFrame line2 = new AppFrame(FRAME.symbol(), FRAME.file(), 2);
        SourceResolver resolver = new SourceResolver(List.of(dir), EMPTY, 6);

        CodeContext cc = resolver.resolve(line2);

        assertEquals(1, cc.snippetStartLine()); // 2 - 6 -> clampado em 1
        assertTrue(cc.snippet().startsWith("package io.whyjvm.sample.checkout;"), cc.snippet());
    }

    @Test
    void windowClampsAtFileEnd(@TempDir Path dir) throws Exception {
        writeSource(dir, REL, SOURCE); // 9 linhas
        AppFrame beyond = new AppFrame(FRAME.symbol(), FRAME.file(), 999);
        SourceResolver resolver = new SourceResolver(List.of(dir), EMPTY, 6);

        CodeContext cc = resolver.resolve(beyond);

        assertEquals(CodeContext.Origin.SOURCE_DIR, cc.origin());
        assertTrue(cc.snippet().trim().endsWith("}"), cc.snippet());
        assertEquals(999, cc.line()); // a linha reportada nao muda, so a janela e clampada
    }

    @Test
    void pathTraversalIsRejected(@TempDir Path dir) throws Exception {
        // Um "fonte" fora da raiz configurada nao deve ser lido.
        Path secret = dir.getParent().resolve("secret.java");
        Files.writeString(secret, "TOP SECRET\n");
        AppFrame evil = new AppFrame("io.app.Foo.bar", "../../secret.java", 1);
        SourceResolver resolver = new SourceResolver(List.of(dir), EMPTY, 2);

        assertEquals(CodeContext.Origin.UNAVAILABLE, resolver.resolve(evil).origin());
    }
}
