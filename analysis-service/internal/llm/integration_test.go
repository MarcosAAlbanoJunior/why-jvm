package llm

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"

	"github.com/whyjvm/analysis-service/internal/api"
	"github.com/whyjvm/analysis-service/internal/incident"
	"github.com/whyjvm/analysis-service/internal/store"
)

// TestInvestigateEndToEndWithClaudeClient exercita o circuito inteiro do servico
// com um cliente Claude REAL apontado para um LLM mockado: ingest -> store ->
// /investigate -> loop do agente -> mapeamento de wire do provider -> tools ->
// Laudo. Prova que as pecas se ligam sem rede real e sem o lado Java (a fixture
// e o contrato).
func TestInvestigateEndToEndWithClaudeClient(t *testing.T) {
	// LLM mockado: 1a chamada pede a tool triage; 2a devolve o laudo final.
	var calls atomic.Int32
	mockLLM := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if calls.Add(1) == 1 {
			_, _ = io.WriteString(w, `{"stop_reason":"tool_use","content":[
				{"type":"tool_use","id":"toolu_1","name":"triage","input":{"incidentId":"x"}}]}`)
			return
		}
		_, _ = io.WriteString(w, `{"stop_reason":"end_turn","content":[{"type":"text","text":
			"{\"causa_raiz\":\"pausa de GC dominou a latencia\",\"confianca\":\"alta\",\"evidencia\":[\"GC 812ms\"]}"}]}`)
	}))
	defer mockLLM.Close()

	provider := NewClaude("test-key", "")
	provider.baseURL = mockLLM.URL

	st, err := store.NewFileStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	srv := api.NewServer(st, "seg", provider)

	// ingest da fixture pelo servidor real
	raw := readFixture(t, "incident-slow-gc.json")
	var rec incident.Record
	if err := json.Unmarshal(raw, &rec); err != nil {
		t.Fatal(err)
	}
	ingest := httptest.NewRequest(http.MethodPost, "/v1/incidents", bytes.NewReader(raw))
	ingest.Header.Set("Authorization", "Bearer seg")
	rr := httptest.NewRecorder()
	srv.ServeHTTP(rr, ingest)
	if rr.Code != http.StatusAccepted {
		t.Fatalf("ingest: status=%d", rr.Code)
	}

	// investigate
	inv := httptest.NewRequest(http.MethodPost,
		"/v1/incidents/"+url.PathEscape(rec.IncidentID)+"/investigate", nil)
	inv.Header.Set("Authorization", "Bearer seg")
	rr2 := httptest.NewRecorder()
	srv.ServeHTTP(rr2, inv)
	if rr2.Code != http.StatusOK {
		t.Fatalf("investigate: status=%d body=%s", rr2.Code, rr2.Body)
	}

	var laudo struct {
		Tipo      string   `json:"tipo"`
		CausaRaiz string   `json:"causa_raiz"`
		Confianca string   `json:"confianca"`
		Evidencia []string `json:"evidencia"`
	}
	if err := json.Unmarshal(rr2.Body.Bytes(), &laudo); err != nil {
		t.Fatalf("laudo nao e JSON: %v\n%s", err, rr2.Body)
	}
	if laudo.Tipo != "SLOW" || laudo.Confianca != "alta" {
		t.Fatalf("laudo inesperado: %+v", laudo)
	}
	if calls.Load() != 2 {
		t.Fatalf("esperava 2 chamadas ao LLM (triage + laudo), veio %d", calls.Load())
	}
}

func readFixture(t *testing.T, name string) []byte {
	t.Helper()
	dir, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	for range 8 {
		p := filepath.Join(dir, "schema", "fixtures", name)
		if b, err := os.ReadFile(p); err == nil {
			return b
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			break
		}
		dir = parent
	}
	t.Fatalf("fixture %s nao encontrada", name)
	return nil
}
