package llm

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/whyjvm/analysis-service/internal/agent"
)

// sampleContext exercita o mapeamento: system, user, assistant(tool_use), tool(result).
func sampleContext() ([]agent.Message, []agent.ToolSpec) {
	ctx := []agent.Message{
		{Role: agent.RoleSystem, Content: "voce e um analista"},
		{Role: agent.RoleUser, Content: "incidentId=inc-1\nTipo: SLOW"},
		{Role: agent.RoleAssistant, ToolCalls: []agent.ToolCall{
			{ID: "toolu_1", Name: "triage", Arguments: map[string]any{"incidentId": "inc-1"}},
		}},
		{Role: agent.RoleTool, Content: "TRIAGEM do incidente inc-1", ToolResultFor: &agent.ToolCall{ID: "toolu_1", Name: "triage"}},
	}
	tools := []agent.ToolSpec{{Name: "triage", Description: "visao geral", InputSchema: map[string]any{"type": "object"}}}
	return ctx, tools
}

func TestClaudeBuildsRequestAndParsesToolCall(t *testing.T) {
	var gotBody []byte
	var gotHeaders http.Header
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotBody, _ = io.ReadAll(r.Body)
		gotHeaders = r.Header
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"stop_reason":"tool_use","content":[
			{"type":"tool_use","id":"toolu_2","name":"get_gc_activity","input":{"incidentId":"inc-1"}}]}`)
	}))
	defer srv.Close()

	p := NewClaude("test-key", "")
	p.baseURL = srv.URL

	ctx, tools := sampleContext()
	resp, err := p.Generate(ctx, tools)
	if err != nil {
		t.Fatal(err)
	}

	// parse da resposta
	if !resp.WantsTools() || len(resp.ToolCalls) != 1 || resp.ToolCalls[0].Name != "get_gc_activity" {
		t.Fatalf("esperava tool call get_gc_activity, veio %+v", resp)
	}

	// headers
	if gotHeaders.Get("x-api-key") != "test-key" || gotHeaders.Get("anthropic-version") != claudeAPIVersion {
		t.Fatalf("headers errados: %v", gotHeaders)
	}

	// request bem-formado
	var got struct {
		Model    string `json:"model"`
		System   string `json:"system"`
		Tools    []struct {
			Name string `json:"name"`
		} `json:"tools"`
		Messages []struct {
			Role    string          `json:"role"`
			Content json.RawMessage `json:"content"`
		} `json:"messages"`
	}
	if err := json.Unmarshal(gotBody, &got); err != nil {
		t.Fatalf("body invalido: %v\n%s", err, gotBody)
	}
	if got.Model != DefaultClaudeModel {
		t.Fatalf("model %q != %q", got.Model, DefaultClaudeModel)
	}
	if got.System != "voce e um analista" {
		t.Fatalf("system errado: %q", got.System)
	}
	if len(got.Tools) != 1 || got.Tools[0].Name != "triage" {
		t.Fatalf("tools erradas: %+v", got.Tools)
	}
	// user, assistant(tool_use), user(tool_result)
	if len(got.Messages) != 3 {
		t.Fatalf("esperava 3 mensagens, veio %d", len(got.Messages))
	}
	if got.Messages[0].Role != "user" || got.Messages[1].Role != "assistant" || got.Messages[2].Role != "user" {
		t.Fatalf("roles errados: %s/%s/%s", got.Messages[0].Role, got.Messages[1].Role, got.Messages[2].Role)
	}
	if !strings.Contains(string(got.Messages[1].Content), "tool_use") ||
		!strings.Contains(string(got.Messages[1].Content), "toolu_1") {
		t.Fatalf("assistant deveria carregar tool_use toolu_1: %s", got.Messages[1].Content)
	}
	if !strings.Contains(string(got.Messages[2].Content), "tool_result") ||
		!strings.Contains(string(got.Messages[2].Content), "toolu_1") {
		t.Fatalf("tool_result deveria referenciar toolu_1: %s", got.Messages[2].Content)
	}
}

func TestClaudeParsesFinalText(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(w, `{"stop_reason":"end_turn","content":[{"type":"text","text":"{\"causa_raiz\":\"x\"}"}]}`)
	}))
	defer srv.Close()

	p := NewClaude("k", "")
	p.baseURL = srv.URL

	resp, err := p.Generate([]agent.Message{{Role: agent.RoleUser, Content: "oi"}}, nil)
	if err != nil {
		t.Fatal(err)
	}
	if resp.WantsTools() || resp.FinalText != `{"causa_raiz":"x"}` {
		t.Fatalf("esperava texto final, veio %+v", resp)
	}
}

func TestClaudeSurfacesRefusal(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(w, `{"stop_reason":"refusal","content":[]}`)
	}))
	defer srv.Close()

	p := NewClaude("k", "")
	p.baseURL = srv.URL

	if _, err := p.Generate([]agent.Message{{Role: agent.RoleUser, Content: "x"}}, nil); err == nil {
		t.Fatal("esperava erro em refusal")
	}
}
