// Package api expoe o ingest HTTP, leitura/tools, investigate e o health. Routing
// por metodo via ServeMux (Go 1.22+); sem dependencia externa.
package api

import (
	"net/http"

	"github.com/whyjvm/analysis-service/internal/agent"
	"github.com/whyjvm/analysis-service/internal/sink"
	"github.com/whyjvm/analysis-service/internal/store"
	"github.com/whyjvm/analysis-service/internal/tools"
)

// jobBuffer limita a fila de investigacoes pendentes (backpressure).
const jobBuffer = 256

// Options sao as dependencias do servidor.
type Options struct {
	Token           string         // bearer exigido; vazio = auth desabilitada (dev)
	Provider        agent.Provider // fronteira de IA (BYO-LLM)
	Sink            sink.Sink      // destino do laudo; nil => LogSink
	AutoInvestigate bool           // modo autonomo: investiga ao receber o incidente
}

// handlers carrega as dependencias compartilhadas pelos endpoints.
type handlers struct {
	store store.Store
	tools *tools.Registry
	agent *agent.Loop
	sink  sink.Sink
	token string
	jobs  chan string // != nil quando o modo autonomo esta ligado
}

// NewServer monta o roteador. Com AutoInvestigate, sobe um worker single-thread
// que investiga cada incidente recebido e publica o laudo no Sink — o agente roda
// um por vez, bounded (custo/rate-limit de LLM).
func NewServer(st store.Store, opts Options) http.Handler {
	if opts.Sink == nil {
		opts.Sink = sink.NewLog()
	}
	reg := tools.NewRegistry(st)
	h := &handlers{
		store: st,
		tools: reg,
		agent: agent.NewLoop(opts.Provider, reg, 0),
		sink:  opts.Sink,
		token: opts.Token,
	}
	if opts.AutoInvestigate {
		h.jobs = make(chan string, jobBuffer)
		go h.investigateWorker()
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
