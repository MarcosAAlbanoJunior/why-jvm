package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func postNoBody(srv http.Handler, path, token string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(http.MethodPost, path, nil)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	rr := httptest.NewRecorder()
	srv.ServeHTTP(rr, req)
	return rr
}

func TestInvestigateReturnsLaudo(t *testing.T) {
	srv, _ := newTestServer(t)
	id := ingestFixture(t, srv, "incident-slow-gc.json")

	rr := postNoBody(srv, incidentPath(id)+"/investigate", testToken)
	if rr.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", rr.Code, rr.Body)
	}
	var laudo struct {
		Tipo      string `json:"tipo"`
		Endpoint  string `json:"endpoint"`
		Confianca string `json:"confianca"`
	}
	if err := json.Unmarshal(rr.Body.Bytes(), &laudo); err != nil {
		t.Fatalf("laudo nao e JSON: %v", err)
	}
	if laudo.Tipo != "SLOW" || laudo.Endpoint != "POST /checkout" {
		t.Fatalf("laudo inesperado: %+v", laudo)
	}
	if laudo.Confianca == "" {
		t.Fatal("laudo sem confianca")
	}
}

func TestInvestigateUnknownIncident(t *testing.T) {
	srv, _ := newTestServer(t)
	if rr := postNoBody(srv, incidentPath("nope")+"/investigate", testToken); rr.Code != http.StatusNotFound {
		t.Fatalf("status=%d (esperado 404)", rr.Code)
	}
}
