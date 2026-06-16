package api

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"github.com/whyjvm/analysis-service/internal/agent"
	"github.com/whyjvm/analysis-service/internal/store"
)

const testToken = "test-token"

var fixtures = []string{
	"incident-error.json",
	"incident-slow-gc.json",
	"incident-slow-lock.json",
}

func newTestServer(t *testing.T) (http.Handler, store.Store) {
	t.Helper()
	st, err := store.NewFileStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	return NewServer(st, Options{Token: testToken, Provider: agent.NewStub()}), st
}

// repoRoot sobe a partir do working dir do teste ate achar o schema canonico.
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
	t.Fatal("repo root (schema/incident-record.v1.json) nao encontrado a partir do working dir")
	return ""
}

func fixture(t *testing.T, name string) []byte {
	t.Helper()
	b, err := os.ReadFile(filepath.Join(repoRoot(t), "schema", "fixtures", name))
	if err != nil {
		t.Fatalf("ler fixture %s: %v", name, err)
	}
	return b
}

func post(srv http.Handler, body []byte, token string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(http.MethodPost, "/v1/incidents", bytes.NewReader(body))
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	rr := httptest.NewRecorder()
	srv.ServeHTTP(rr, req)
	return rr
}

func TestIngestAcceptsFixtures(t *testing.T) {
	srv, st := newTestServer(t)
	for _, f := range fixtures {
		body := fixture(t, f)
		rr := post(srv, body, testToken)
		if rr.Code != http.StatusAccepted {
			t.Fatalf("%s: status=%d body=%s", f, rr.Code, rr.Body)
		}
		var meta struct {
			IncidentID string `json:"incidentId"`
		}
		if err := json.Unmarshal(body, &meta); err != nil {
			t.Fatalf("%s: %v", f, err)
		}
		if has, _ := st.Has(meta.IncidentID); !has {
			t.Fatalf("%s: incidente %s nao foi persistido", f, meta.IncidentID)
		}
	}
}

func TestIngestIsIdempotent(t *testing.T) {
	srv, _ := newTestServer(t)
	body := fixture(t, "incident-error.json")
	if rr := post(srv, body, testToken); rr.Code != http.StatusAccepted {
		t.Fatalf("1o envio: status=%d", rr.Code)
	}
	if rr := post(srv, body, testToken); rr.Code != http.StatusAccepted {
		t.Fatalf("2o envio (idempotente) deveria aceitar: status=%d", rr.Code)
	}
}

func TestIngestRejectsBadToken(t *testing.T) {
	srv, _ := newTestServer(t)
	if rr := post(srv, fixture(t, "incident-error.json"), "wrong"); rr.Code != http.StatusUnauthorized {
		t.Fatalf("status=%d (esperado 401)", rr.Code)
	}
}

func TestIngestRejectsWrongSchemaVersion(t *testing.T) {
	srv, _ := newTestServer(t)
	body := fixture(t, "incident-error.json")
	tampered := bytes.Replace(body, []byte(`"schemaVersion": 1`), []byte(`"schemaVersion": 2`), 1)
	if bytes.Equal(tampered, body) {
		t.Fatal("nao consegui adulterar o schemaVersion da fixture")
	}
	if rr := post(srv, tampered, testToken); rr.Code != http.StatusBadRequest {
		t.Fatalf("status=%d body=%s (esperado 400)", rr.Code, rr.Body)
	}
}

func TestIngestRejectsMalformedJSON(t *testing.T) {
	srv, _ := newTestServer(t)
	if rr := post(srv, []byte("{not json"), testToken); rr.Code != http.StatusBadRequest {
		t.Fatalf("status=%d (esperado 400)", rr.Code)
	}
}

func TestHealthz(t *testing.T) {
	srv, _ := newTestServer(t)
	req := httptest.NewRequest(http.MethodGet, "/healthz", nil)
	rr := httptest.NewRecorder()
	srv.ServeHTTP(rr, req)
	if rr.Code != http.StatusOK {
		t.Fatalf("status=%d (esperado 200)", rr.Code)
	}
}
