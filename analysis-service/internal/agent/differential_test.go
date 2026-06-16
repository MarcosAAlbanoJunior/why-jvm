package agent

import (
	"strings"
	"testing"

	"github.com/whyjvm/analysis-service/internal/incident"
)

func joinDiff(d []string) string { return strings.Join(d, " | ") }

func TestDifferentialExternalWaitRulesOutJvmNoise(t *testing.T) {
	// db-search?slow=true: thread dormiu 10s; alocacao alta (143MB) e JVM-wide ruido.
	rec := &incident.Record{
		DurationMs: 10005,
		TriageSignals: &incident.TriageSignals{
			GcCount: 3, LongestGcPauseMs: 4, TotalGcPauseMs: 8,
			TotalLockWaitMs: 0, TotalAllocBytes: 143 * 1024 * 1024,
		},
		Dimensions: incident.Dimensions{
			ThreadActivity: &incident.ThreadActivity{Thread: "http-nio-1", SleepMs: 10000, CpuSamples: 0},
		},
	}
	d := joinDiff(differential(rec))

	for _, want := range []string{"Pausas de GC", "Contencao de lock", "CPU: a thread", "ruido de fundo"} {
		if !strings.Contains(d, want) {
			t.Fatalf("esperava descartar %q em:\n%s", want, d)
		}
	}
	// A espera externa NAO e descartada — ela e a causa.
	if strings.Contains(d, "Espera externa") {
		t.Fatalf("nao deveria descartar espera externa (e a causa):\n%s", d)
	}
}

func TestDifferentialKeepsLiveCandidates(t *testing.T) {
	// slow-gc: GC 812ms + thread em CPU (38 amostras) + alocacao alta.
	rec := &incident.Record{
		DurationMs: 4200,
		TriageSignals: &incident.TriageSignals{
			GcCount: 3, LongestGcPauseMs: 812, TotalGcPauseMs: 1340,
			TotalLockWaitMs: 0, TotalAllocBytes: 1288490188,
		},
		Dimensions: incident.Dimensions{
			ThreadActivity: &incident.ThreadActivity{Thread: "http-nio-3", CpuSamples: 38},
		},
	}
	d := joinDiff(differential(rec))

	// Descartados: lock e espera externa.
	if !strings.Contains(d, "Contencao de lock") || !strings.Contains(d, "Espera externa") {
		t.Fatalf("esperava descartar lock e espera externa:\n%s", d)
	}
	// Candidatos vivos NAO descartados: GC, CPU, alocacao.
	for _, live := range []string{"Pausas de GC", "CPU: a thread", "Pressao de alocacao"} {
		if strings.Contains(d, live) {
			t.Fatalf("nao deveria descartar candidato vivo %q:\n%s", live, d)
		}
	}
}

func TestDifferentialNilWithoutSignals(t *testing.T) {
	if d := differential(&incident.Record{DurationMs: 100}); d != nil {
		t.Fatalf("sem triageSignals nao deveria descartar nada, veio %v", d)
	}
}
