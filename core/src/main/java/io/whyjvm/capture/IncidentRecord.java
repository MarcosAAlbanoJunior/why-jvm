package io.whyjvm.capture;

import io.whyjvm.trigger.IncidentType;

import java.time.Instant;

/**
 * O pacote de evidencia duravel de um incidente, ja com os agregados extraidos do
 * JFR. E o que as tools leem (sem re-parsear o snapshot) e o que, no split (Fase 5),
 * serializa para o JSON do contrato e viaja ao servico de analise em Go.
 *
 * <p>Os nomes dos componentes batem 1:1 com as chaves do schema
 * {@code schema/incident-record.v1.json} — a serializacao Jackson e direta.
 *
 * <p>Construcao em duas etapas, refletindo a captura: {@link #initial} monta a
 * parte barata na thread do request (escalares + exception do span); a extracao
 * dos agregados roda fora da thread e enriquece o registro via
 * {@link #withEvidence}.
 */
public record IncidentRecord(
        int schemaVersion,
        String incidentId,
        Instant capturedAt,
        String endpoint,
        IncidentType type,
        String fingerprint,
        String threadName,
        long durationMs,
        int occurrenceCount,
        ExceptionInfo exception,
        Baseline baseline,
        TriageSignals triageSignals,
        Dimensions dimensions,
        JvmContext jvmContext,
        String jfrArtifactUri,
        CodeContext codeContext
) {

    /** Versao do contrato que este registro produz. */
    public static final int SCHEMA_VERSION = 1;

    /**
     * Registro inicial, montado na thread do request: escalares + exception, sem
     * agregados ainda (triageSignals null, dimensoes vazias). A evidencia JFR
     * entra depois, fora da thread, via {@link #withEvidence}.
     */
    public static IncidentRecord initial(
            String incidentId, Instant capturedAt, String endpoint, IncidentType type,
            String fingerprint, String threadName, long durationMs, int occurrenceCount,
            ExceptionInfo exception, Baseline baseline, JvmContext jvmContext) {
        return new IncidentRecord(
                SCHEMA_VERSION, incidentId, capturedAt, endpoint, type, fingerprint,
                threadName, durationMs, occurrenceCount, exception, baseline,
                null, Dimensions.empty(), jvmContext, null, null);
    }

    /** Copia com os agregados extraidos do snapshot e o ponteiro para o .jfr. */
    public IncidentRecord withEvidence(TriageSignals signals, Dimensions dims, String jfrArtifactUri) {
        return new IncidentRecord(
                schemaVersion, incidentId, capturedAt, endpoint, type, fingerprint,
                threadName, durationMs, occurrenceCount, exception, baseline,
                signals, dims, jvmContext, jfrArtifactUri, codeContext);
    }

    /** Copia com o contexto de codigo do frame suspeito (Tier 2, resolvido na captura). */
    public IncidentRecord withCodeContext(CodeContext codeContext) {
        return new IncidentRecord(
                schemaVersion, incidentId, capturedAt, endpoint, type, fingerprint,
                threadName, durationMs, occurrenceCount, exception, baseline,
                triageSignals, dimensions, jvmContext, jfrArtifactUri, codeContext);
    }
}
