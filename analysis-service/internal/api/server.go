// Package api expoe o ingest HTTP e o health. Routing por metodo via ServeMux
// (Go 1.22+); sem dependencia externa.
package api

import (
	"net/http"

	"github.com/whyjvm/analysis-service/internal/agent"
	"github.com/whyjvm/analysis-service/internal/store"
	"github.com/whyjvm/analysis-service/internal/tools"
)

// handlers carrega as dependencias compartilhadas pelos endpoints.
type handlers struct {
	store store.Store
	tools *tools.Registry
	agent *agent.Loop
	token string // bearer exigido; vazio = auth desabilitada (dev)
}

// NewServer monta o roteador com os endpoints do servico. O provider do agente e
// injetado (BYO-LLM): stub, Claude ou Gemini, escolhido por env via llm.FromEnv.
func NewServer(st store.Store, token string, provider agent.Provider) http.Handler {
	reg := tools.NewRegistry(st)
	h := &handlers{
		store: st,
		tools: reg,
		agent: agent.NewLoop(provider, reg, 0),
		token: token,
	}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", h.health)
	mux.HandleFunc("POST /v1/incidents", h.ingest)
	mux.HandleFunc("GET /v1/incidents/{id}", h.getIncident)
	mux.HandleFunc("GET /v1/incidents/{id}/tools/{tool}", h.runTool)
	mux.HandleFunc("POST /v1/incidents/{id}/investigate", h.investigate)
	mux.HandleFunc("GET /v1/tools", h.listTools)
	return mux
}
