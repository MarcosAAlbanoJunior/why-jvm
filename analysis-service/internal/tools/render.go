package tools

import (
	"fmt"
	"math"
	"strings"

	"github.com/whyjvm/analysis-service/internal/incident"
)

// renderException porta GetExceptionDetailsTool.
func renderException(r *incident.Record) string {
	if r.Exception == nil {
		return fmt.Sprintf("O incidente %s nao tem exception anexada (tipo: %s).", r.IncidentID, r.Type)
	}
	return fmt.Sprintf(`Endpoint: %s
Tipo: %s
Exception: %s
Mensagem: %s
Stack trace:
%s
`, r.Endpoint, r.Type, r.Exception.Type, deref(r.Exception.Message), deref(r.Exception.StackTrace))
}

// renderGcActivity porta GetGcActivityTool.summarize.
func renderGcActivity(r *incident.Record) string {
	gc := r.Dimensions.GcActivity
	if gc == nil || len(gc.Pauses) == 0 {
		return "Nenhuma coleta de GC na janela."
	}
	worst := gc.Pauses[0]
	for _, p := range gc.Pauses[1:] {
		if p.LongestPauseMs > worst.LongestPauseMs {
			worst = p
		}
	}
	return fmt.Sprintf(`Coletas de GC na janela: %d
Pausa total: %dms
Maior pausa: %dms (%s, causa: %s)
`, gc.Count, gc.TotalPauseMs, worst.LongestPauseMs, worst.Name, worst.Cause)
}

// renderAllocationHotspots porta GetAllocationHotspotsTool.summarize.
func renderAllocationHotspots(r *incident.Record) string {
	alloc := r.Dimensions.AllocationHotspots
	if alloc == nil || len(alloc.TopSites) == 0 {
		return "Nenhuma amostra de alocacao na janela."
	}
	var sb strings.Builder
	fmt.Fprintf(&sb, "Total amostrado na janela: %d KB em %d amostras (avalie se a magnitude e relevante "+
		"frente a latencia; KB poucos sao ruido de fundo, nao causa).\n",
		alloc.TotalSampledBytes/1024, alloc.SampleCount)
	sb.WriteString("Top call sites por bytes alocados (amostrado):\n")
	for _, s := range alloc.TopSites {
		fmt.Fprintf(&sb, "- %s: %d KB (%d%%)\n", s.Site, s.Bytes/1024, int64(math.Round(s.Pct)))
	}
	return sb.String()
}

// renderLockContention porta GetLockContentionTool.summarize.
func renderLockContention(r *incident.Record) string {
	lock := r.Dimensions.LockContention
	if lock == nil || len(lock.TopSites) == 0 {
		return "Nenhuma contencao de lock relevante na janela."
	}
	var sb strings.Builder
	sb.WriteString("Contencao de lock na janela:\n")
	fmt.Fprintf(&sb, "Espera total: %dms em %d eventos\n", lock.TotalWaitMs, lock.EventCount)
	for _, s := range lock.TopSites {
		fmt.Fprintf(&sb, "- %s (monitor %s): %dms\n", s.Site, s.MonitorClass, s.WaitMs)
	}
	return sb.String()
}

// renderThreadActivity porta GetThreadActivityTool.
func renderThreadActivity(r *incident.Record) string {
	a := r.Dimensions.ThreadActivity
	if a == nil {
		return "Thread do incidente nao registrada (ou sem snapshot); sem como atribuir a atividade."
	}
	waiting := a.SleepMs + a.IoMs + a.LockMs + a.ParkMs
	half := float64(r.DurationMs) * 0.5

	var conclusion string
	switch {
	case a.CpuSamples == 0 && float64(waiting) >= half:
		conclusion = "A thread passou a maior parte ESPERANDO (" + dominantWait(a) + "), nao executando " +
			"trabalho na JVM. A lentidao e de espera/bloqueio — nao de CPU, alocacao ou GC desta thread."
	case a.CpuSamples > 0 && float64(waiting) < half:
		conclusion = fmt.Sprintf("A thread esteve majoritariamente em CPU (%d amostras); investigue o "+
			"trabalho/algoritmo desta thread (alocacao/execucao), nao espera externa.", a.CpuSamples)
	default:
		conclusion = "Atividade mista entre espera e CPU; pondere o dominante (" + dominantWait(a) + ")."
	}

	sleepSite := ""
	if a.SleepSite != nil && *a.SleepSite != "" {
		sleepSite = " (em " + *a.SleepSite + ")"
	}
	return fmt.Sprintf(`Atividade da thread %s (latencia do incidente: %dms):
- Thread.sleep: %dms%s
- Espera de I/O (socket/arquivo): %dms
- Espera de lock (monitor): %dms
- Park (LockSupport/concorrencia): %dms
- Amostras de CPU: %d

%s
`, a.Thread, r.DurationMs, a.SleepMs, sleepSite, a.IoMs, a.LockMs, a.ParkMs, a.CpuSamples, conclusion)
}

// renderSlowTraces porta GetSlowTracesTool: arvore do trace, span dominante e N+1.
func renderSlowTraces(r *incident.Record) string {
	traces := r.Dimensions.SlowTraces
	if len(traces) == 0 {
		return "Sem arvore de trace para este incidente — a app nao gerou sub-spans na janela " +
			"(cliente HTTP/JDBC instrumentado?). Nada a atribuir por span."
	}
	incidentMs := max(r.DurationMs, 1)
	var sb strings.Builder
	fmt.Fprintf(&sb, "Spans mais lentos do trace (latencia do incidente: %dms):\n", incidentMs)
	hasNPlusOne := false
	for _, t := range traces {
		if strings.HasPrefix(t.Span, "N+1") {
			hasNPlusOne = true
		}
		fmt.Fprintf(&sb, "  - %s: self %dms, total %dms (~%d%% da latencia)\n",
			t.Span, t.SelfMs, t.TotalMs, t.TotalMs*100/incidentMs)
	}
	sb.WriteString("\n")
	if hasNPlusOne {
		sb.WriteString("N+1 detectado: chamadas identicas repetidas dominam o tempo — agrupe/elimine as " +
			"repeticoes (batch, join, cache) em vez de otimizar uma chamada isolada.\n")
	} else {
		sb.WriteString("O span no topo concentra o self time; investigue-o (consulta/downstream/algoritmo) " +
			"antes de qualquer dimensao JVM-wide.\n")
	}
	return sb.String()
}

// renderBaseline mostra o comportamento normal do endpoint.
func renderBaseline(r *incident.Record) string {
	b := r.Baseline
	if b == nil {
		return fmt.Sprintf("Sem baseline para %s ainda (amostras insuficientes).", r.Endpoint)
	}
	return fmt.Sprintf(`Baseline de %s:
- p99 movel: %.0fms
- amostras: %d
- limiar de lentidao: %.1fx o p99 (dispara acima de %.0fms)
`, r.Endpoint, b.P99Ms, b.SampleCount, b.ThresholdMultiplier, b.P99Ms*b.ThresholdMultiplier)
}

func dominantWait(a *incident.ThreadActivity) string {
	best := a.SleepMs
	label := "Thread.sleep"
	if a.IoMs > best {
		best, label = a.IoMs, "I/O"
	}
	if a.LockMs > best {
		best, label = a.LockMs, "lock"
	}
	if a.ParkMs > best {
		best, label = a.ParkMs, "park"
	}
	if best == 0 {
		return "sem espera medida"
	}
	return label
}

func deref(s *string) string {
	if s == nil {
		return ""
	}
	return *s
}
