// Package api expoe o ingest HTTP e o health. Routing por metodo via ServeMux
// (Go 1.22+); sem dependencia externa.
package api

import (
	"net/http"

	"github.com/whyjvm/analysis-service/internal/store"
)

// handlers carrega as dependencias compartilhadas pelos endpoints.
type handlers struct {
	store store.Store
	token string // bearer exigido; vazio = auth desabilitada (dev)
}

// NewServer monta o roteador com os endpoints do servico.
func NewServer(st store.Store, token string) http.Handler {
	h := &handlers{store: st, token: token}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", h.health)
	mux.HandleFunc("POST /v1/incidents", h.ingest)
	return mux
}
