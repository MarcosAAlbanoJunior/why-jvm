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
	// SLOW: as tools de GC/alocacao sao JVM-WIDE (somam TODAS as threads) e nao
	// sabem se o sinal esta na thread do request. get_thread_activity e a UNICA que
	// filtra pela thread — entao e SEMPRE o primeiro drill no SLOW. A dimensao mais
	// alta vira so um candidato a confirmar (ou descartar, se a thread so esperou).
	dur := max(r.DurationMs, 1)
	candidate := slowCandidate(sig, dur)
	return hypothesis{
		fmt.Sprintf("latencia alta (%dms). Sinais JVM-wide (somam TODAS as threads, podem ser ruido de "+
			"fundo): %s. Confirme PRIMEIRO se a thread do request esperou (causa externa) ou trabalhou "+
			"(JVM) antes de culpar qualquer dimensao.", dur, candidate),
		"a confirmar via thread_activity (candidato: " + candidate + ")",
		"get_thread_activity",
	}
}

// slowCandidate aponta a dimensao JVM-wide mais alta como hipotese a confirmar —
// nao como veredito (pode ser ruido de outra thread).
func slowCandidate(sig *incident.TriageSignals, dur int64) string {
	share := float64(dur) * shareThreshold
	if float64(sig.LongestGcPauseMs) >= share && sig.LongestGcPauseMs >= sig.TotalLockWaitMs {
		return fmt.Sprintf("gc (maior pausa %dms, ~%d%% da latencia)",
			sig.LongestGcPauseMs, pct(sig.LongestGcPauseMs, dur))
	}
	if float64(sig.TotalLockWaitMs) >= share {
		return fmt.Sprintf("lock (espera total %dms, ~%d%% da latencia)",
			sig.TotalLockWaitMs, pct(sig.TotalLockWaitMs, dur))
	}
	if sig.TotalAllocBytes > allocBytesThreshold {
		return fmt.Sprintf("alocacao (%d MB amostrados, JVM-wide)", sig.TotalAllocBytes/(1024*1024))
	}
	return "nenhuma dimensao JVM com sinal forte (provavel causa externa: espera/IO/downstream)"
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
