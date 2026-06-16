// Package tools expoe o catalogo de tools como leitores finos sobre o
// IncidentRecord ja persistido. Cada tool le uma fatia do JSON e devolve um
// agregado em texto — o mesmo formato que as tools Java produzem, para o agente
// receber o contexto consistente. NUNCA parseia JFR (isso e do lado Java).
package tools

import (
	"encoding/json"
	"errors"
	"fmt"

	"github.com/whyjvm/analysis-service/internal/incident"
	"github.com/whyjvm/analysis-service/internal/store"
)

// ErrIncidentNotFound indica que o id pedido nao esta no store.
var ErrIncidentNotFound = errors.New("incidente nao encontrado")

// ErrUnknownTool indica um nome de tool fora do catalogo.
var ErrUnknownTool = errors.New("tool desconhecida")

// Tool e uma entrada do catalogo: nome, descricao e a renderizacao do agregado.
type Tool struct {
	Name        string
	Description string
	render      func(*incident.Record) string
}

// Registry resolve tools por nome e carrega o incidente do store sob demanda.
type Registry struct {
	store store.Store
	byName map[string]Tool
	order  []string
}

// NewRegistry monta o catalogo. A ordem espelha o McpToolRegistry do core.
func NewRegistry(st store.Store) *Registry {
	r := &Registry{store: st, byName: map[string]Tool{}}
	r.register(Tool{"triage",
		"Visao geral e hipotese inicial: tipo, exception, latencia e a dimensao suspeita. Chame PRIMEIRO.",
		renderTriage})
	r.register(Tool{"get_exception_details",
		"Stack trace da exception, mensagem e span de origem do incidente.",
		renderException})
	r.register(Tool{"get_thread_activity",
		"O que a thread do request fez na janela: sleep, I/O, lock, park e CPU (espera vs trabalho).",
		renderThreadActivity})
	r.register(Tool{"get_gc_activity",
		"Pausas de GC na janela: numero de coletas, pausa total e a maior pausa com a causa.",
		renderGcActivity})
	r.register(Tool{"get_allocation_hotspots",
		"Top call sites por bytes alocados na janela (amostrado via JFR).",
		renderAllocationHotspots})
	r.register(Tool{"get_lock_contention",
		"Threads bloqueadas em monitor na janela: call sites por tempo de espera.",
		renderLockContention})
	r.register(Tool{"get_endpoint_baseline",
		"Comportamento normal do endpoint (p99 movel e limiar), para comparar.",
		renderBaseline})
	return r
}

func (r *Registry) register(t Tool) {
	r.byName[t.Name] = t
	r.order = append(r.order, t.Name)
}

// List devolve o catalogo na ordem de registro.
func (r *Registry) List() []Tool {
	out := make([]Tool, 0, len(r.order))
	for _, name := range r.order {
		out = append(out, r.byName[name])
	}
	return out
}

// Call carrega o incidente e roda a tool, devolvendo o agregado em texto.
func (r *Registry) Call(incidentID, toolName string) (string, error) {
	t, ok := r.byName[toolName]
	if !ok {
		return "", fmt.Errorf("%w: %s", ErrUnknownTool, toolName)
	}
	rec, err := r.load(incidentID)
	if err != nil {
		return "", err
	}
	return t.render(rec), nil
}

// load le e desserializa o incidente do store.
func (r *Registry) load(incidentID string) (*incident.Record, error) {
	raw, found, err := r.store.Find(incidentID)
	if err != nil {
		return nil, err
	}
	if !found {
		return nil, fmt.Errorf("%w: %s", ErrIncidentNotFound, incidentID)
	}
	var rec incident.Record
	if err := json.Unmarshal(raw, &rec); err != nil {
		return nil, fmt.Errorf("incidente %s corrompido no store: %w", incidentID, err)
	}
	return &rec, nil
}
