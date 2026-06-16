package api

import (
	"encoding/json"
	"log"

	"github.com/whyjvm/analysis-service/internal/incident"
)

// enqueue empurra um incidente para a fila de investigacao (no-op se o modo
// autonomo estiver desligado). Nunca bloqueia: se a fila encher, registra e segue
// — o /investigate manual continua disponivel.
func (h *handlers) enqueue(incidentID string) {
	if h.jobs == nil {
		return
	}
	select {
	case h.jobs <- incidentID:
	default:
		log.Printf("fila de investigacao cheia; incidente %s nao sera investigado automaticamente", incidentID)
	}
}

// investigateWorker processa a fila um incidente por vez (bounded). Single-thread:
// o agente roda serializado, limitando custo e rate-limit do LLM.
func (h *handlers) investigateWorker() {
	for id := range h.jobs {
		h.runInvestigation(id)
	}
}

func (h *handlers) runInvestigation(incidentID string) {
	raw, found, err := h.store.Find(incidentID)
	if err != nil || !found {
		log.Printf("auto-investigacao: incidente %s indisponivel: %v", incidentID, err)
		return
	}
	var rec incident.Record
	if err := json.Unmarshal(raw, &rec); err != nil {
		log.Printf("auto-investigacao: incidente %s corrompido no store: %v", incidentID, err)
		return
	}
	laudo, err := h.agent.Investigate(&rec)
	if err != nil {
		log.Printf("auto-investigacao falhou para %s: %v", incidentID, err)
		return
	}
	if err := h.sink.Publish(laudo); err != nil {
		log.Printf("falha ao publicar laudo de %s: %v", incidentID, err)
	}
}
