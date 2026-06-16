package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
)

func get(srv http.Handler, path, token string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(http.MethodGet, path, nil)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	rr := httptest.NewRecorder()
	srv.ServeHTTP(rr, req)
	return rr
}

// ingestFixture POSTa uma fixture e devolve o incidentId persistido.
func ingestFixture(t *testing.T, srv http.Handler, name string) string {
	t.Helper()
	body := fixture(t, name)
	if rr := post(srv, body, testToken); rr.Code != http.StatusAccepted {
		t.Fatalf("ingest %s: status=%d", name, rr.Code)
	}
	var meta struct {
		IncidentID string `json:"incidentId"`
	}
	if err := json.Unmarshal(body, &meta); err != nil {
		t.Fatal(err)
	}
	return meta.IncidentID
}

func incidentPath(id string) string {
	return "/v1/incidents/" + url.PathEscape(id)
}

func TestGetIncident(t *testing.T) {
	srv, _ := newTestServer(t)
	id := ingestFixture(t, srv, "incident-slow-gc.json")

	rr := get(srv, incidentPath(id), testToken)
	if rr.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", rr.Code, rr.Body)
	}
	var meta struct {
		IncidentID string `json:"incidentId"`
	}
	if err := json.Unmarshal(rr.Body.Bytes(), &meta); err != nil {
		t.Fatalf("resposta nao e JSON: %v", err)
	}
	if meta.IncidentID != id {
		t.Fatalf("id devolvido %q != %q", meta.IncidentID, id)
	}

	if rr := get(srv, incidentPath("inexistente"), testToken); rr.Code != http.StatusNotFound {
		t.Fatalf("incidente desconhecido: status=%d (esperado 404)", rr.Code)
	}
}

func TestRunToolHTTP(t *testing.T) {
	srv, _ := newTestServer(t)
	id := ingestFixture(t, srv, "incident-slow-gc.json")

	rr := get(srv, incidentPath(id)+"/tools/triage", testToken)
	if rr.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", rr.Code, rr.Body)
	}
	if !strings.Contains(rr.Body.String(), "Proximo passo sugerido: get_thread_activity") {
		t.Fatalf("triage inesperada:\n%s", rr.Body)
	}

	if rr := get(srv, incidentPath(id)+"/tools/nope", testToken); rr.Code != http.StatusNotFound {
		t.Fatalf("tool desconhecida: status=%d (esperado 404)", rr.Code)
	}
	if rr := get(srv, incidentPath("nope")+"/tools/triage", testToken); rr.Code != http.StatusNotFound {
		t.Fatalf("incidente desconhecido: status=%d (esperado 404)", rr.Code)
	}
}

func TestListTools(t *testing.T) {
	srv, _ := newTestServer(t)
	rr := get(srv, "/v1/tools", testToken)
	if rr.Code != http.StatusOK {
		t.Fatalf("status=%d", rr.Code)
	}
	if !strings.Contains(rr.Body.String(), "triage") {
		t.Fatalf("catalogo sem triage: %s", rr.Body)
	}
}

func TestReadEndpointsRequireAuth(t *testing.T) {
	srv, _ := newTestServer(t)
	id := ingestFixture(t, srv, "incident-error.json")
	if rr := get(srv, incidentPath(id), "wrong"); rr.Code != http.StatusUnauthorized {
		t.Fatalf("status=%d (esperado 401)", rr.Code)
	}
}
