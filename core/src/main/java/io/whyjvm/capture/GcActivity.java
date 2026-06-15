package io.whyjvm.capture;

import java.util.List;

/**
 * Pausas de GC na janela (dimensao {@code gcActivity}). Agregado pronto: numero
 * de coletas, pausa total e a lista de pausas com causa.
 */
public record GcActivity(
        int count,
        long totalPauseMs,
        List<Pause> pauses
) {
    /** Uma coleta de GC: coletor, causa e duracao. */
    public record Pause(
            String name,
            String cause,
            long longestPauseMs,
            long sumPausesMs
    ) {
    }
}
