package sink

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/whyjvm/analysis-service/internal/agent"
)

// Webhook publica o laudo num incoming webhook que aceita {"text": ...} — Slack e
// Mattermost. A URL e o unico segredo (vem do ambiente). Para Discord/Evolution o
// formato do corpo difere; daria pra parametrizar depois.
type Webhook struct {
	url  string
	http *http.Client
}

// NewWebhook cria o sink apontando para a URL do webhook.
func NewWebhook(url string) *Webhook {
	return &Webhook{url: url, http: &http.Client{Timeout: 10 * time.Second}}
}

// Publish implementa Sink: POSTa o laudo formatado (mrkdwn) como {"text": ...}.
func (w *Webhook) Publish(l agent.Laudo) error {
	body, err := json.Marshal(map[string]string{"text": formatSlack(l)})
	if err != nil {
		return err
	}
	resp, err := w.http.Post(w.url, "application/json", bytes.NewReader(body))
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		return fmt.Errorf("webhook respondeu %d", resp.StatusCode)
	}
	return nil
}

// formatSlack monta a mensagem em mrkdwn do Slack.
func formatSlack(l agent.Laudo) string {
	var sb strings.Builder
	fmt.Fprintf(&sb, ":rotating_light: *Incidente investigado* — `%s` (%s)\n", l.Endpoint, l.Tipo)
	fmt.Fprintf(&sb, "*Causa raiz:* %s\n", l.CausaRaiz)
	fmt.Fprintf(&sb, "*Confianca:* %s\n", l.Confianca)
	if len(l.Evidencia) > 0 {
		sb.WriteString("*Evidencia:*\n")
		for _, e := range l.Evidencia {
			fmt.Fprintf(&sb, "• %s\n", e)
		}
	}
	if len(l.HipotesesDescartadas) > 0 {
		sb.WriteString("*Hipoteses descartadas:*\n")
		for _, h := range l.HipotesesDescartadas {
			fmt.Fprintf(&sb, "✓ %s\n", h)
		}
	}
	if l.CodeContext != nil {
		fmt.Fprintf(&sb, "*Codigo do metodo suspeito:*\n```%s```\n", l.CodeContext.Render())
	}
	if l.CorrecaoSugerida != "" {
		fmt.Fprintf(&sb, "*Correcao sugerida:* %s", l.CorrecaoSugerida)
	}
	return sb.String()
}
