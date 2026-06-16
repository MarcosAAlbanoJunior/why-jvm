// Package api expoe o ingest HTTP e o health. Routing por metodo via ServeMux
// (Go 1.22+); sem dependencia externa.
package api

import (
	"net/http"

	"github.com/whyjvm/analysis-service/internal/store"
	"github.com/whyjvm/analysis-service/internal/tools"
)

// handlers carrega as dependencias compartilhadas pelos endpoints.
type handlers struct {
	store store.Store
	tools *tools.Registry
	token string // bearer exigido; vazio = auth desabilitada (dev)
}

// NewServer monta o roteador com os endpoints do servico.
func NewServer(st store.Store, token string) http.Handler {
	h := &handlers{store: st, tools: tools.NewRegistry(st), token: token}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", h.health)
	mux.HandleFunc("POST /v1/incidents", h.ingest)
	mux.HandleFunc("GET /v1/incidents/{id}", h.getIncident)
	mux.HandleFunc("GET /v1/incidents/{id}/tools/{tool}", h.runTool)
	mux.HandleFunc("GET /v1/tools", h.listTools)
	return mux
}
