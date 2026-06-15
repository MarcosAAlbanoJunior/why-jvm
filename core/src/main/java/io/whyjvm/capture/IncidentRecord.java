package io.whyjvm.capture;

import io.whyjvm.trigger.IncidentType;

import java.nio.file.Path;
import java.time.Instant;

/**
 * O pacote de evidencia duravel de um incidente. E o que o servidor MCP le e
 * agrega; o agente nunca enxerga nada alem do que as tools extraem daqui.
 *
 * <p>Contem os dados do trace que falhou e o caminho do snapshot JFR da janela,
 * de onde se extraem GC, alocacao, lock e amostras de CPU.
 */
public record IncidentRecord(
        String incidentId,
        Instant capturedAt,
        String endpoint,
        IncidentType type,
        // Identidade do incidente: (endpoint, assinatura do erro). Mesma
        // assinatura = mesmo incidente; e a chave do dedup e, na Fase 2, da triagem.
        String fingerprint,
        // Nome da thread que atendeu o request, para atribuir os eventos JFR da
        // janela a ela (distinguir espera da propria thread de ruido de fundo).
        String threadName,
        long durationMs,
        // Detalhes da exception (quando type == ERROR), extraidos do span.
        String exceptionType,
        String exceptionMessage,
        String exceptionStackTrace,
        // Caminho do snapshot JFR da janela [T-delta, T]. Pode ser null se o
        // JFR nao estava ativo no ambiente.
        Path jfrSnapshot
) {
}
