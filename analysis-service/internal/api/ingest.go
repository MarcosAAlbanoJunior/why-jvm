package api

import (
	"encoding/json"
	"io"
	"net/http"

	"github.com/whyjvm/analysis-service/internal/incident"
)

// maxBody limita o corpo do POST: o IncidentRecord e agregado, nao o .jfr cru.
const maxBody = 8 << 20 // 8 MiB

// ingest recebe um IncidentRecord JSON, valida e persiste de forma idempotente.
// Responde 202 rapido — a investigacao (agente) roda async em fases posteriores.
func (h *handlers) ingest(w http.ResponseWriter, r *http.Request) {
	if !h.authorized(r) {
		writeError(w, http.StatusUnauthorized, "token de ingest invalido ou ausente")
		return
	}

	body, err := io.ReadAll(io.LimitReader(r.Body, maxBody))
	if err != nil {
		writeError(w, http.StatusBadRequest, "falha ao ler o corpo")
		return
	}

	var rec incident.Record
	if err := json.Unmarshal(body, &rec); err != nil {
		writeError(w, http.StatusBadRequest, "JSON invalido: "+err.Error())
		return
	}
	if err := rec.Validate(); err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}

	// Persiste o JSON cru (lossless), idempotente por incidentId.
	if err := h.store.Save(rec.IncidentID, body); err != nil {
		writeError(w, http.StatusInternalServerError, "falha ao persistir o incidente")
		return
	}

	// Modo autonomo: dispara a investigacao async (no-op se desligado).
	h.enqueue(rec.IncidentID)

	writeJSON(w, http.StatusAccepted, map[string]string{
		"incidentId": rec.IncidentID,
		"status":     "accepted",
	})
}

// authorized: se nao ha token configurado, auth fica desabilitada (dev).
func (h *handlers) authorized(r *http.Request) bool {
	if h.token == "" {
		return true
	}
	return r.Header.Get("Authorization") == "Bearer "+h.token
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

func writeError(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}
