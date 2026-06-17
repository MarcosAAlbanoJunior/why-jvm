package io.whyjvm.capture;

import java.util.List;

/**
 * Junta os dois passos do code-aware RCA num so: do stack da exception de um
 * incidente, acha o frame de topo da app ({@link AppFrame}) e resolve seu fonte
 * ({@link SourceResolver}). E o ponto que a captura chama no {@code extract}, fora
 * da thread do request.
 *
 * <p>Devolve {@code null} (sem code context) quando o incidente nao tem exception
 * com stack, ou quando nenhum frame da app e reconhecido — honesto: so anexa
 * quando ha algo concreto a apontar.
 */
public final class CodeContextResolver {

    private final List<String> appBasePackages;
    private final SourceResolver source;

    public CodeContextResolver(List<String> appBasePackages, SourceResolver source) {
        this.appBasePackages = appBasePackages == null ? List.of() : List.copyOf(appBasePackages);
        this.source = source;
    }

    /** O code context do frame de topo da app, ou {@code null} se nao houver o que apontar. */
    public CodeContext forIncident(IncidentRecord record) {
        ExceptionInfo exc = record.exception();
        if (exc == null || exc.stackTrace() == null) {
            return null;
        }
        return AppFrame.top(exc.stackTrace(), appBasePackages)
                .map(source::resolve)
                .orElse(null);
    }
}
