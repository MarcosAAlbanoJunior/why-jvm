package api

import (
	"encoding/json"
	"net/http"

	"github.com/whyjvm/analysis-service/internal/incident"
)

// investigate roda o loop do agente sobre um incidente e devolve o laudo. No
// modo autonomo (fase seguinte) isto sera disparado pelo ingest e despachado pro
// sink; aqui e sob demanda (modo interativo).
func (h *handlers) investigate(w http.ResponseWriter, r *http.Request) {
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
	var rec incident.Record
	if err := json.Unmarshal(raw, &rec); err != nil {
		writeError(w, http.StatusInternalServerError, "incidente corrompido no store")
		return
	}

	laudo, err := h.agent.Investigate(&rec)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "falha na investigacao: "+err.Error())
		return
	}
	writeJSON(w, http.StatusOK, laudo)
}
