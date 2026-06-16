package sink

import (
	"errors"
	"fmt"
	"os"
)

// FromEnv seleciona o sink por WHYJVM_SINK (log|slack). slack exige
// WHYJVM_SLACK_WEBHOOK_URL (ou WHYJVM_WEBHOOK_URL). Default: log.
func FromEnv() (Sink, error) {
	switch os.Getenv("WHYJVM_SINK") {
	case "", "log":
		return NewLog(), nil
	case "slack", "webhook":
		url := os.Getenv("WHYJVM_SLACK_WEBHOOK_URL")
		if url == "" {
			url = os.Getenv("WHYJVM_WEBHOOK_URL")
		}
		if url == "" {
			return nil, errors.New("WHYJVM_SINK=slack exige WHYJVM_SLACK_WEBHOOK_URL")
		}
		return NewWebhook(url), nil
	default:
		return nil, fmt.Errorf("WHYJVM_SINK desconhecido: %q (use log|slack)", os.Getenv("WHYJVM_SINK"))
	}
}
