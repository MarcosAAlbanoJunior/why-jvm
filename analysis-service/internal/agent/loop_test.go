package agent

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/whyjvm/analysis-service/internal/incident"
	"github.com/whyjvm/analysis-service/internal/store"
	"github.com/whyjvm/analysis-service/internal/tools"
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

func setupLoop(t *testing.T, fixtureName string, provider Provider, maxCalls int) (*Loop, *incident.Record) {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join(repoRoot(t), "schema", "fixtures", fixtureName))
	if err != nil {
		t.Fatalf("ler fixture: %v", err)
	}
	var rec incident.Record
	if err := json.Unmarshal(raw, &rec); err != nil {
		t.Fatal(err)
	}
	st, err := store.NewFileStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if err := st.Save(rec.IncidentID, raw); err != nil {
		t.Fatal(err)
	}
	return NewLoop(provider, tools.NewRegistry(st), maxCalls), &rec
}

func TestStubLoopConvergesOnSlowGc(t *testing.T) {
	loop, rec := setupLoop(t, "incident-slow-gc.json", NewStub(), 0)
	laudo, err := loop.Investigate(rec)
	if err != nil {
		t.Fatal(err)
	}
	if laudo.Tipo != "SLOW" || laudo.Endpoint != "POST /checkout" {
		t.Fatalf("laudo herdou mal o incidente: %+v", laudo)
	}
	if laudo.Confianca != "baixa" {
		t.Fatalf("stub deveria dar confianca baixa: %q", laudo.Confianca)
	}
	// A triagem roteia SLOW para get_thread_activity primeiro; o stub segue e usa
	// a evidencia da thread do request (espera vs trabalho).
	if len(laudo.Evidencia) == 0 || !strings.Contains(strings.Join(laudo.Evidencia, " "), "Atividade da thread") {
		t.Fatalf("esperava evidencia de thread_activity, veio %v", laudo.Evidencia)
	}
}

func TestStubLoopConvergesOnError(t *testing.T) {
	loop, rec := setupLoop(t, "incident-error.json", NewStub(), 0)
	laudo, err := loop.Investigate(rec)
	if err != nil {
		t.Fatal(err)
	}
	if laudo.Tipo != "ERROR" {
		t.Fatalf("esperava tipo ERROR, veio %q", laudo.Tipo)
	}
	if len(laudo.Evidencia) == 0 {
		t.Fatal("esperava alguma evidencia coletada")
	}
}

type alwaysTools struct{}

func (alwaysTools) Name() string { return "always" }
func (alwaysTools) Generate([]Message, []ToolSpec) (Response, error) {
	return Response{ToolCalls: []ToolCall{{ID: "x", Name: "triage", Arguments: map[string]any{"incidentId": "x"}}}}, nil
}

func TestLoopRespectsToolCallCeiling(t *testing.T) {
	loop, rec := setupLoop(t, "incident-error.json", alwaysTools{}, 2)
	laudo, err := loop.Investigate(rec)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(laudo.CausaRaiz, "teto de turnos") {
		t.Fatalf("esperava laudo de teto de turnos, veio %q", laudo.CausaRaiz)
	}
}

type erroring struct{}

func (erroring) Name() string                          { return "err" }
func (erroring) Generate([]Message, []ToolSpec) (Response, error) {
	return Response{}, errors.New("boom")
}

func TestLoopPropagatesProviderError(t *testing.T) {
	loop, rec := setupLoop(t, "incident-error.json", erroring{}, 0)
	if _, err := loop.Investigate(rec); err == nil {
		t.Fatal("esperava erro propagado do provider")
	}
}

func TestExtractJSON(t *testing.T) {
	cases := []struct{ in, want string }{
		{"```json\n{\"a\":1}\n```", `{"a":1}`},
		{"Aqui esta:\n{\"b\":2}\nfim", `{"b":2}`},
		{"sem json aqui", "sem json aqui"},
	}
	for _, c := range cases {
		if got := extractJSON(c.in); got != c.want {
			t.Fatalf("extractJSON(%q)=%q, queria %q", c.in, got, c.want)
		}
	}
}

func TestParseLaudoFallbackOnNonJSON(t *testing.T) {
	rec := &incident.Record{Endpoint: "GET /x", Type: "SLOW"}
	laudo := parseLaudo("isto nao e json", rec)
	if laudo.CausaRaiz != "isto nao e json" {
		t.Fatalf("esperava texto livre como causa_raiz, veio %q", laudo.CausaRaiz)
	}
	if laudo.Confianca != "baixa" || laudo.Endpoint != "GET /x" {
		t.Fatalf("fallbacks do incidente errados: %+v", laudo)
	}
}
