package io.whyjvm.capture;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolve o fonte do {@link AppFrame} suspeito numa janela de linhas — o passo que
 * torna o code-aware RCA possivel no modelo javaagent, onde so o agente in-JVM tem
 * handle no codigo da app monitorada.
 *
 * <p>Cadeia, na ordem (degradacao honesta — nunca fabrica):
 * <ol>
 *   <li>{@link CodeContext.Origin#SOURCE_JAR}: fonte no classpath (source jar /
 *       resource), via {@code ClassLoader.getResourceAsStream}. Javaagent-native,
 *       sem config.</li>
 *   <li>{@link CodeContext.Origin#SOURCE_DIR}: diretorios de fonte configurados
 *       (dev/CI), com guarda contra path traversal.</li>
 *   <li>{@link CodeContext.Origin#UNAVAILABLE}: fonte indisponivel em runtime — o
 *       laudo diz isso, em vez de inventar.</li>
 * </ol>
 */
public final class SourceResolver {

    /** Linhas de contexto antes e depois da linha do frame. */
    public static final int DEFAULT_CONTEXT_LINES = 6;

    private final List<Path> sourceDirs;
    private final ClassLoader classLoader;
    private final int contextLines;

    public SourceResolver(List<Path> sourceDirs, ClassLoader classLoader, int contextLines) {
        this.sourceDirs = sourceDirs == null ? List.of() : List.copyOf(sourceDirs);
        this.classLoader = classLoader != null ? classLoader : SourceResolver.class.getClassLoader();
        this.contextLines = Math.max(0, contextLines);
    }

    public SourceResolver(List<Path> sourceDirs) {
        this(sourceDirs, null, DEFAULT_CONTEXT_LINES);
    }

    /** Janela de fonte ao redor do frame, ou {@code UNAVAILABLE} se nao houver fonte. */
    public CodeContext resolve(AppFrame frame) {
        String resourcePath = frame.sourceResourcePath();

        List<String> lines = readFromClasspath(resourcePath);
        if (lines != null) {
            return window(frame, lines, CodeContext.Origin.SOURCE_JAR);
        }
        for (Path dir : sourceDirs) {
            lines = readFromDir(dir, resourcePath);
            if (lines != null) {
                return window(frame, lines, CodeContext.Origin.SOURCE_DIR);
            }
        }
        return CodeContext.unavailable(frame.symbol(), frame.file(), frame.line());
    }

    private List<String> readFromClasspath(String resourcePath) {
        try (InputStream in = classLoader.getResourceAsStream(resourcePath)) {
            return in == null ? null : readLines(in);
        } catch (IOException e) {
            return null;
        }
    }

    private List<String> readFromDir(Path dir, String resourcePath) {
        Path base = dir.toAbsolutePath().normalize();
        Path target = base.resolve(resourcePath).normalize();
        if (!target.startsWith(base) || !Files.isRegularFile(target)) {
            return null; // guarda contra path traversal / inexistente
        }
        try (InputStream in = Files.newInputStream(target)) {
            return readLines(in);
        } catch (IOException e) {
            return null;
        }
    }

    private static List<String> readLines(InputStream in) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    private CodeContext window(AppFrame frame, List<String> lines, CodeContext.Origin origin) {
        int total = lines.size();
        if (total == 0) {
            return CodeContext.unavailable(frame.symbol(), frame.file(), frame.line());
        }
        int center = Math.min(Math.max(frame.line(), 1), total);
        int from = Math.max(1, center - contextLines);
        int to = Math.min(total, center + contextLines);
        String snippet = String.join("\n", lines.subList(from - 1, to));
        return new CodeContext(frame.symbol(), frame.file(), frame.line(), origin, snippet, from);
    }
}
