// Package llm implementa providers LLM reais (BYO-LLM) sobre a fronteira
// agent.Provider: clientes HTTP diretos pras APIs (sem SDK pesado), selecionados
// por env. A key vem do ambiente; o servico nunca a guarda.
package llm

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/whyjvm/analysis-service/internal/agent"
)

const defaultTimeout = 60 * time.Second

// postJSON faz um POST JSON e desserializa a resposta. Erro em status != 2xx.
func postJSON(client *http.Client, url string, headers map[string]string, reqBody, respOut any) error {
	b, err := json.Marshal(reqBody)
	if err != nil {
		return err
	}
	req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(b))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return err
	}
	if resp.StatusCode/100 != 2 {
		return fmt.Errorf("HTTP %d: %s", resp.StatusCode, snippet(data))
	}
	return json.Unmarshal(data, respOut)
}

func snippet(b []byte) string {
	if len(b) > 500 {
		return string(b[:500])
	}
	return string(b)
}

// systemText junta o conteudo das mensagens de sistema do contexto.
func systemText(context []agent.Message) string {
	var parts []string
	for _, m := range context {
		if m.Role == agent.RoleSystem && m.Content != "" {
			parts = append(parts, m.Content)
		}
	}
	return strings.Join(parts, "\n\n")
}
