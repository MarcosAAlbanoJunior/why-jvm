// Package sink despacha o laudo para onde o humano vê. LogSink hoje; Slack /
// e-mail / WhatsApp depois (B4). Espelha a fronteira Sink do core Java.
package sink

import (
	"log"
	"strings"

	"github.com/whyjvm/analysis-service/internal/agent"
)

// Sink e o destino de um laudo. Nao deve bloquear por muito tempo.
type Sink interface {
	Publish(laudo agent.Laudo) error
}

// Log escreve o laudo no log padrao do servico. E o default e a base do B4.
type Log struct{}

// NewLog cria o sink de log.
func NewLog() *Log { return &Log{} }

// Publish implementa Sink.
func (*Log) Publish(l agent.Laudo) error {
	var sb strings.Builder
	sb.WriteString("\n========== LAUDO ==========\n")
	sb.WriteString("endpoint:   " + l.Endpoint + "\n")
	sb.WriteString("tipo:       " + l.Tipo + "\n")
	sb.WriteString("confianca:  " + l.Confianca + "\n")
	sb.WriteString("causa_raiz: " + l.CausaRaiz + "\n")
	if len(l.Evidencia) > 0 {
		sb.WriteString("evidencia:\n")
		for _, e := range l.Evidencia {
			sb.WriteString("  - " + e + "\n")
		}
	}
	if len(l.HipotesesDescartadas) > 0 {
		sb.WriteString("descartadas:\n")
		for _, h := range l.HipotesesDescartadas {
			sb.WriteString("  ✓ " + h + "\n")
		}
	}
	if l.CorrecaoSugerida != "" {
		sb.WriteString("correcao:   " + l.CorrecaoSugerida + "\n")
	}
	sb.WriteString("===========================")
	log.Print(sb.String())
	return nil
}
