package tools

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/whyjvm/analysis-service/internal/incident"
	"github.com/whyjvm/analysis-service/internal/store"
)

func repoRoot(t *testing.T) string {
	t.Helper()
	dir, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	for range 8 {
		if _, err := os.Stat(filepath.Join(dir, "schema", "incident-record.v1.json")); err == nil {
			return dir
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			break
		}
		dir = parent
	}
	t.Fatal("repo root nao encontrado")
	return ""
}

// setup cria um registry com uma fixture ja persistida e devolve o incidentId.
func setup(t *testing.T, fixtureName string) (*Registry, string) {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join(repoRoot(t), "schema", "fixtures", fixtureName))
	if err != nil {
		t.Fatalf("ler fixture %s: %v", fixtureName, err)
	}
	var rec incident.Record
	if err := json.Unmarshal(raw, &rec); err != nil {
		t.Fatalf("%s: %v", fixtureName, err)
	}
	st, err := store.NewFileStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if err := st.Save(rec.IncidentID, raw); err != nil {
		t.Fatal(err)
	}
	return NewRegistry(st), rec.IncidentID
}

func call(t *testing.T, reg *Registry, id, tool string) string {
	t.Helper()
	out, err := reg.Call(id, tool)
	if err != nil {
		t.Fatalf("Call(%s): %v", tool, err)
	}
	return out
}

func assertContains(t *testing.T, s, sub string) {
	t.Helper()
	if !strings.Contains(s, sub) {
		t.Fatalf("esperava conter %q em:\n%s", sub, s)
	}
}

func TestTriagePointsToRightDimension(t *testing.T) {
	cases := []struct {
		fixture   string
		suspect   string
		nextStep  string
	}{
		{"incident-error.json", "Dimensao suspeita: exception", "get_exception_details"},
		{"incident-slow-gc.json", "Dimensao suspeita: gc", "get_gc_activity"},
		{"incident-slow-lock.json", "Dimensao suspeita: lock", "get_lock_contention"},
	}
	for _, c := range cases {
		t.Run(c.fixture, func(t *testing.T) {
			reg, id := setup(t, c.fixture)
			out := call(t, reg, id, "triage")
			assertContains(t, out, c.suspect)
			assertContains(t, out, c.nextStep)
		})
	}
}

func TestExceptionRender(t *testing.T) {
	reg, id := setup(t, "incident-error.json")
	out := call(t, reg, id, "get_exception_details")
	assertContains(t, out, "java.lang.NullPointerException")
	assertContains(t, out, "InvoiceBuilder.buildLineItems")
}

func TestGcActivityRender(t *testing.T) {
	reg, id := setup(t, "incident-slow-gc.json")
	out := call(t, reg, id, "get_gc_activity")
	assertContains(t, out, "Coletas de GC na janela: 3")
	assertContains(t, out, "Maior pausa: 812ms")
}

func TestAllocationRender(t *testing.T) {
	reg, id := setup(t, "incident-slow-gc.json")
	out := call(t, reg, id, "get_allocation_hotspots")
	assertContains(t, out, "InvoiceBuilder.buildLineItems")
	assertContains(t, out, "(73%)")
}

func TestLockRender(t *testing.T) {
	reg, id := setup(t, "incident-slow-lock.json")
	out := call(t, reg, id, "get_lock_contention")
	assertContains(t, out, "InventoryCache.refresh")
	assertContains(t, out, "2200ms")
}

func TestThreadActivityRender(t *testing.T) {
	reg, id := setup(t, "incident-slow-gc.json")
	out := call(t, reg, id, "get_thread_activity")
	// slow-gc: cpuSamples=38, sem espera -> trabalho em CPU.
	assertContains(t, out, "majoritariamente em CPU")
}

func TestBaselineRender(t *testing.T) {
	reg, id := setup(t, "incident-slow-gc.json")
	out := call(t, reg, id, "get_endpoint_baseline")
	assertContains(t, out, "p99 movel: 380ms")
}

func TestUnknownToolAndIncident(t *testing.T) {
	reg, id := setup(t, "incident-error.json")
	if _, err := reg.Call(id, "nope"); !errors.Is(err, ErrUnknownTool) {
		t.Fatalf("esperava ErrUnknownTool, veio %v", err)
	}
	if _, err := reg.Call("inexistente", "triage"); !errors.Is(err, ErrIncidentNotFound) {
		t.Fatalf("esperava ErrIncidentNotFound, veio %v", err)
	}
}
