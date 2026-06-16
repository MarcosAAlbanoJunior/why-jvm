package api

import (
	"net/http"
	"testing"
	"time"

	"github.com/whyjvm/analysis-service/internal/agent"
	"github.com/whyjvm/analysis-service/internal/store"
)

// capturingSink registra o laudo publicado para o teste verificar.
type capturingSink struct{ ch chan agent.Laudo }

func (s *capturingSink) Publish(l agent.Laudo) error {
	s.ch <- l
	return nil
}

func TestAutoInvestigatePublishesLaudo(t *testing.T) {
	st, err := store.NewFileStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	cap := &capturingSink{ch: make(chan agent.Laudo, 1)}
	srv := NewServer(st, Options{
		Token:           testToken,
		Provider:        agent.NewStub(),
		Sink:            cap,
		AutoInvestigate: true,
	})

	// Ingest dispara a investigacao automatica (worker async).
	if rr := post(srv, fixture(t, "incident-slow-gc.json"), testToken); rr.Code != http.StatusAccepted {
		t.Fatalf("ingest: status=%d", rr.Code)
	}

	select {
	case laudo := <-cap.ch:
		if laudo.Tipo != "SLOW" || laudo.Endpoint != "POST /checkout" {
			t.Fatalf("laudo inesperado: %+v", laudo)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("modo autonomo nao publicou o laudo no sink")
	}
}

func TestNoAutoInvestigateWhenDisabled(t *testing.T) {
	st, err := store.NewFileStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	cap := &capturingSink{ch: make(chan agent.Laudo, 1)}
	srv := NewServer(st, Options{Token: testToken, Provider: agent.NewStub(), Sink: cap}) // AutoInvestigate=false

	if rr := post(srv, fixture(t, "incident-slow-gc.json"), testToken); rr.Code != http.StatusAccepted {
		t.Fatalf("ingest: status=%d", rr.Code)
	}

	select {
	case <-cap.ch:
		t.Fatal("nao deveria investigar automaticamente com AutoInvestigate desligado")
	case <-time.After(300 * time.Millisecond):
		// ok: nada publicado
	}
}
