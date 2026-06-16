package tools

import (
	"fmt"
	"strings"

	"github.com/whyjvm/analysis-service/internal/incident"
)

// Constantes da triagem (espelham TriageTool no core).
const (
	// shareThreshold: fracao da latencia a partir da qual uma dimensao e suspeita.
	shareThreshold = 0.10
	// allocBytesThreshold: acima disto, a alocacao amostrada e um sinal.
	allocBytesThreshold = 50 * 1024 * 1024
)

type hypothesis struct {
	text      string
	dimension string
	nextStep  string
}

// renderTriage porta TriageTool.report: hipotese inicial a partir dos sinais
// headline ja calculados pelo lado Java. Deterministico, sem LLM.
func renderTriage(r *incident.Record) string {
	sig := r.TriageSignals
	h := hypothesize(r, sig)

	exceptionLine := "(nenhuma)"
	if r.Exception != nil {
		exceptionLine = r.Exception.Type
		if r.Exception.Message != nil && *r.Exception.Message != "" {
			exceptionLine += " — \"" + firstLine(*r.Exception.Message) + "\""
		}
	}
	excDim := "sem exception"
	if r.Exception != nil {
		excDim = "ANOMALA (exception anexada ao span)"
	}

	return fmt.Sprintf(`TRIAGEM do incidente %s
Tipo: %s
Endpoint: %s
Latencia: %dms
Exception: %s

Dimensoes:
- exception:  %s
- gc:         %s
- lock:       %s
- alocacao:   %s
- downstream: nao avaliado (requer captura do trace; get_slow_traces pendente)

Hipotese inicial: %s
Dimensao suspeita: %s
Proximo passo sugerido: %s
`, r.IncidentID, r.Type, r.Endpoint, r.DurationMs, exceptionLine,
		excDim, gcLine(sig), lockLine(sig), allocLine(sig),
		h.text, h.dimension, h.nextStep)
}

func hypothesize(r *incident.Record, sig *incident.TriageSignals) hypothesis {
	if r.Type == "ERROR" && r.Exception != nil {
		return hypothesis{
			"erro de aplicacao — uma exception nao tratada propagou ate a borda do request.",
			"exception", "get_exception_details"}
	}
	if r.Type == "ERROR" {
		return hypothesis{
			"request terminou com status de erro, mas sem exception anexada ao span.",
			"exception (sem stack)", "get_exception_details"}
	}
	// SLOW: escolhe a dimensao que mais pesa na latencia.
	if sig == nil {
		return hypothesis{
			"sem snapshot JFR — a captura nao gerou evidencia desta janela.",
			"indefinida (sem JFR)", "(sem evidencia para drill-down)"}
	}
	dur := max(r.DurationMs, 1)
	share := float64(dur) * shareThreshold
	switch {
	case sig.LongestGcPauseMs >= sig.TotalLockWaitMs && float64(sig.LongestGcPauseMs) >= share:
		return hypothesis{
			fmt.Sprintf("pausa de GC de %dms na janela (~%d%% da latencia).",
				sig.LongestGcPauseMs, pct(sig.LongestGcPauseMs, dur)),
			"gc", "get_gc_activity"}
	case sig.TotalLockWaitMs > sig.LongestGcPauseMs && float64(sig.TotalLockWaitMs) >= share:
		return hypothesis{
			fmt.Sprintf("contencao de lock somou %dms de espera (~%d%% da latencia).",
				sig.TotalLockWaitMs, pct(sig.TotalLockWaitMs, dur)),
			"lock", "get_lock_contention"}
	case sig.TotalAllocBytes > allocBytesThreshold:
		return hypothesis{
			fmt.Sprintf("alocacao alta na janela (%d MB amostrados); pode estar pressionando o GC.",
				sig.TotalAllocBytes/(1024*1024)),
			"alocacao", "get_allocation_hotspots"}
	default:
		return hypothesis{
			"latencia alta sem sinal forte de GC/lock/alocacao JVM-wide — confirme o que a thread do " +
				"request fez (espera vs trabalho) antes de concluir; nao culpe alocacao/GC triviais.",
			"a confirmar (espera vs trabalho)", "get_thread_activity"}
	}
}

func gcLine(s *incident.TriageSignals) string {
	if s == nil {
		return "sem snapshot JFR"
	}
	if s.GcCount == 0 {
		return "sem coletas na janela"
	}
	return fmt.Sprintf("maior pausa %dms, %d coletas (total %dms)",
		s.LongestGcPauseMs, s.GcCount, s.TotalGcPauseMs)
}

func lockLine(s *incident.TriageSignals) string {
	if s == nil {
		return "sem snapshot JFR"
	}
	if s.TotalLockWaitMs == 0 {
		return "sem contencao relevante"
	}
	return fmt.Sprintf("espera total %dms", s.TotalLockWaitMs)
}

func allocLine(s *incident.TriageSignals) string {
	if s == nil {
		return "sem snapshot JFR"
	}
	if s.TotalAllocBytes == 0 {
		return "sem amostras"
	}
	return fmt.Sprintf("%d MB amostrados", s.TotalAllocBytes/(1024*1024))
}

func pct(part, whole int64) int64 {
	return part * 100 / whole
}

func firstLine(s string) string {
	if i := strings.IndexAny(s, "\r\n"); i >= 0 {
		return s[:i]
	}
	return s
}
