package io.whyjvm.capture;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * O frame de topo da APP no stack de uma exception — o ponto onde o bug se
 * manifesta, que o Tier 2 (code-aware RCA) vai abrir no fonte. Pula frames de JDK
 * e framework para nao narrar "NPE em Method.invoke": o que importa e
 * {@code CustomerService.calculateDiscount}, nao a plumbing reflexiva.
 *
 * <p>Distingue app de infra por <b>base-packages da app</b> (include-list, o jeito
 * confiavel — o agente sabe qual app monitora). Sem base-packages, cai num
 * exclude-list de prefixos de infra conhecidos (heuristica de fallback).
 */
public record AppFrame(String symbol, String file, int line) {

    /**
     * Linha de frame: {@code [modulo/ | loader//]pacote.Classe.metodo(Arquivo.ext:linha)}.
     * O prefixo opcional de modulo/classloader (ate a ultima '/') e descartado.
     * Frames sem {@code :linha} (Native Method / Unknown Source) nao casam.
     */
    private static final Pattern FRAME = Pattern.compile(
            "^(?:.*/)?([\\w.$]+)\\.([\\w$<>]+)\\(([^()]+):(\\d+)\\)$");

    /** Prefixos de infra para o fallback sem base-packages (cada um termina em '.'). */
    private static final List<String> INFRA = List.of(
            "java.", "javax.", "jakarta.", "jdk.", "sun.", "com.sun.",
            "org.springframework.", "org.apache.", "org.hibernate.", "org.slf4j.",
            "ch.qos.", "io.micrometer.", "reactor.", "io.netty.", "org.eclipse.",
            "org.junit.", "org.gradle.", "io.whyjvm.agent.", "io.whyjvm.capture.");

    /**
     * Primeiro frame da app no stack (varrendo do topo), ou vazio se nenhum frame
     * de codigo da app for reconhecido (ex.: stack so de infra, ou trace vazio).
     *
     * @param stackTrace      o stack trace completo como string ({@code ExceptionInfo.stackTrace})
     * @param appBasePackages pacotes-raiz da app; quando vazio, usa o exclude-list de infra
     */
    public static Optional<AppFrame> top(String stackTrace, Collection<String> appBasePackages) {
        if (stackTrace == null || stackTrace.isBlank()) {
            return Optional.empty();
        }
        for (String raw : stackTrace.split("\\R")) {
            String line = stripFramePrefix(raw);
            Matcher m = FRAME.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String declaringClass = m.group(1);
            if (isApp(declaringClass, appBasePackages)) {
                return Optional.of(new AppFrame(
                        declaringClass + "." + m.group(2),
                        m.group(3),
                        Integer.parseInt(m.group(4))));
            }
        }
        return Optional.empty();
    }

    /**
     * Caminho do recurso/fonte deste frame: o pacote da classe declarante como
     * diretorios + o arquivo do frame. Ex.: {@code io/app/checkout/CustomerService.java}.
     * Deriva o pacote da classe (nao do nome do arquivo) para acertar inner classes
     * — {@code io.app.Svc$Worker} + {@code Svc.java} -> {@code io/app/Svc.java}.
     */
    public String sourceResourcePath() {
        int methodDot = symbol.lastIndexOf('.');
        String declaringClass = methodDot < 0 ? symbol : symbol.substring(0, methodDot);
        int pkgDot = declaringClass.lastIndexOf('.');
        String pkg = pkgDot < 0 ? "" : declaringClass.substring(0, pkgDot);
        return pkg.isEmpty() ? file : pkg.replace('.', '/') + "/" + file;
    }

    /** Remove o indentador e o "at " inicial do frame ("\tat io.app.Foo..." -> "io.app.Foo..."). */
    private static String stripFramePrefix(String raw) {
        String t = raw.strip();
        return t.startsWith("at ") ? t.substring(3).strip() : t;
    }

    private static boolean isApp(String declaringClass, Collection<String> appBasePackages) {
        if (appBasePackages != null && !appBasePackages.isEmpty()) {
            for (String base : appBasePackages) {
                if (declaringClass.equals(base) || declaringClass.startsWith(base + ".")) {
                    return true;
                }
            }
            return false;
        }
        for (String infra : INFRA) {
            if (declaringClass.startsWith(infra)) {
                return false;
            }
        }
        return true;
    }
}
