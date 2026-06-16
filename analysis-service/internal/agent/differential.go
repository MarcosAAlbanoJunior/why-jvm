package agent

import (
	"fmt"

	"github.com/whyjvm/analysis-service/internal/incident"
)

const (
	// differentialShare: fracao da latencia abaixo da qual uma dimensao e descartada.
	differentialShare = 0.10
	// allocNoiseBytes: alocacao amostrada abaixo disto e ruido, nao causa.
	allocNoiseBytes = 50 * 1024 * 1024
	// healthyHeapPct: uso de heap abaixo disto descarta pressao de memoria.
	healthyHeapPct = 85
)

// differential monta as hipoteses DESCARTADAS de forma deterministica, a partir
// dos sinais ja medidos — nao do LLM. Cada item e ancorado num numero. Codifica a
// distincao JVM-wide vs thread do request: alocacao/CPU so sao causa se a thread
// do request trabalhou (uma pausa de GC, stop-the-world, afeta todas as threads —
// essa nao se descarta por espera). Sem snapshot JFR, nao descarta nada (honesto).
func differential(rec *incident.Record) []string {
	sig := rec.TriageSignals
	if sig == nil {
		return nil
	}
	dur := max(rec.DurationMs, 1)
	share := float64(dur) * differentialShare
	ta := rec.Dimensions.ThreadActivity
	threadWorked := ta != nil && ta.CpuSamples > 0
	threadWaited := ta != nil && float64(ta.SleepMs+ta.IoMs+ta.ParkMs) >= share

	var out []string

	// Pausa de GC: stop-the-world afeta todas as threads, entao so o tamanho conta.
	if float64(sig.LongestGcPauseMs) < share {
		out = append(out, fmt.Sprintf("Pausas de GC: irrelevantes (maior %dms)", sig.LongestGcPauseMs))
	}
	// Contencao de lock / deadlock.
	if float64(sig.TotalLockWaitMs) < share {
		out = append(out, "Contencao de lock / deadlock: sem espera relevante em monitor")
	}
	// CPU (thread do request).
	if ta != nil && ta.CpuSamples == 0 {
		out = append(out, "CPU: a thread do request nao estava em CPU")
	}
	// Alocacao: JVM-wide. So e causa se a thread do request esteve em CPU.
	switch {
	case sig.TotalAllocBytes < allocNoiseBytes:
		out = append(out, fmt.Sprintf("Pressao de alocacao: baixa (%d MB amostrados)",
			sig.TotalAllocBytes/(1024*1024)))
	case !threadWorked:
		out = append(out, fmt.Sprintf("Pressao de alocacao: %d MB amostrados, mas JVM-wide e a thread do "+
			"request nao estava em CPU (ruido de fundo, nao a causa)", sig.TotalAllocBytes/(1024*1024)))
	}
	// Espera externa (I/O / banco / downstream): da thread do request.
	if ta != nil && !threadWaited {
		out = append(out, "Espera externa (I/O / banco / downstream): sem espera relevante na thread do request")
	}
	// Heap: so quando jvmContext for populado (dos MXBeans).
	if jc := rec.JvmContext; jc != nil && jc.HeapMaxMb > 0 {
		if usage := jc.HeapUsedMb * 100 / jc.HeapMaxMb; usage < healthyHeapPct {
			out = append(out, fmt.Sprintf("Heap: uso saudavel (%d%%)", usage))
		}
	}
	return out
}
