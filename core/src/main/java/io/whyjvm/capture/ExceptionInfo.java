package io.whyjvm.capture;

/**
 * Detalhes da exception extraidos do span (quando o incidente e ERROR).
 * Agrupa o que antes eram tres campos soltos no {@code IncidentRecord}.
 * Os nomes dos componentes batem com as chaves do schema v1 ({@code exception}).
 */
public record ExceptionInfo(
        String type,
        String message,
        String stackTrace
) {
}
