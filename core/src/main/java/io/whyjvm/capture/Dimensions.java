package io.whyjvm.capture;

import java.util.List;

/**
 * Agregados por dimensao, ja no formato que cada tool serve. Cada membro pode ser
 * null se o JFR nao tinha aquela dimensao na janela. Portao 2: o agente le so a
 * dimensao que pediu. Espelha o schema v1 ({@code dimensions}).
 */
public record Dimensions(
        GcActivity gcActivity,
        AllocationHotspots allocationHotspots,
        LockContention lockContention,
        ThreadActivity threadActivity,
        List<SlowTrace> slowTraces
) {

    /** Conjunto vazio — usado quando nao ha snapshot JFR na janela. */
    public static Dimensions empty() {
        return new Dimensions(null, null, null, null, null);
    }
}
