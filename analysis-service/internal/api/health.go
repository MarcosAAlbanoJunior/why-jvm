package api

import (
	"io"
	"net/http"
)

// health responde liveness/readiness do servico.
func (h *handlers) health(w http.ResponseWriter, _ *http.Request) {
	w.WriteHeader(http.StatusOK)
	_, _ = io.WriteString(w, "ok")
}
