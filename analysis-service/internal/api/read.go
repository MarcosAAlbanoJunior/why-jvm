package api

import (
	"errors"
	"io"
	"net/http"

	"github.com/whyjvm/analysis-service/internal/tools"
)

// getIncident devolve o JSON cru de um incidente persistido.
func (h *handlers) getIncident(w http.ResponseWriter, r *http.Request) {
	if !h.authorized(r) {
		writeError(w, http.StatusUnauthorized, "token invalido ou ausente")
		return
	}
	raw, found, err := h.store.Find(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusInternalServerError, "falha ao ler o incidente")
		return
	}
	if !found {
		writeError(w, http.StatusNotFound, "incidente nao encontrado")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(raw)
}

// runTool roda uma tool sobre um incidente e devolve o agregado em texto. E a
// porta da frente do modo interativo (o agente, no modo autonomo, chama o
// Registry in-process).
func (h *handlers) runTool(w http.ResponseWriter, r *http.Request) {
	if !h.authorized(r) {
		writeError(w, http.StatusUnauthorized, "token invalido ou ausente")
		return
	}
	out, err := h.tools.Call(r.PathValue("id"), r.PathValue("tool"))
	if err != nil {
		switch {
		case errors.Is(err, tools.ErrIncidentNotFound):
			writeError(w, http.StatusNotFound, "incidente nao encontrado")
		case errors.Is(err, tools.ErrUnknownTool):
			writeError(w, http.StatusNotFound, "tool desconhecida")
		default:
			writeError(w, http.StatusInternalServerError, "falha ao executar a tool")
		}
		return
	}
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	_, _ = io.WriteString(w, out)
}

// listTools devolve o catalogo de tools (nome + descricao).
func (h *handlers) listTools(w http.ResponseWriter, r *http.Request) {
	if !h.authorized(r) {
		writeError(w, http.StatusUnauthorized, "token invalido ou ausente")
		return
	}
	type entry struct {
		Name        string `json:"name"`
		Description string `json:"description"`
	}
	catalog := h.tools.List()
	out := make([]entry, 0, len(catalog))
	for _, t := range catalog {
		out = append(out, entry{t.Name, t.Description})
	}
	writeJSON(w, http.StatusOK, out)
}
