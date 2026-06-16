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

func TestGeminiBuildsRequestAndParsesFunctionCall(t *testing.T) {
	var gotBody []byte
	var gotKey, gotPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotBody, _ = io.ReadAll(r.Body)
		gotKey = r.Header.Get("x-goog-api-key")
		gotPath = r.URL.Path
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"candidates":[{"content":{"role":"model","parts":[
			{"functionCall":{"name":"get_gc_activity","args":{"incidentId":"inc-1"}}}]}}]}`)
	}))
	defer srv.Close()

	p := NewGemini("gkey", "")
	p.baseURL = srv.URL

	ctx, tools := sampleContext()
	resp, err := p.Generate(ctx, tools)
	if err != nil {
		t.Fatal(err)
	}

	if !resp.WantsTools() || len(resp.ToolCalls) != 1 || resp.ToolCalls[0].Name != "get_gc_activity" {
		t.Fatalf("esperava functionCall get_gc_activity, veio %+v", resp)
	}
	if gotKey != "gkey" {
		t.Fatalf("x-goog-api-key errado: %q", gotKey)
	}
	if !strings.Contains(gotPath, DefaultGeminiModel) || !strings.HasSuffix(gotPath, ":generateContent") {
		t.Fatalf("path errado: %q", gotPath)
	}

	var got struct {
		SystemInstruction struct {
			Parts []struct {
				Text string `json:"text"`
			} `json:"parts"`
		} `json:"system_instruction"`
		Contents []struct {
			Role  string          `json:"role"`
			Parts json.RawMessage `json:"parts"`
		} `json:"contents"`
		Tools []struct {
			FunctionDeclarations []struct {
				Name string `json:"name"`
			} `json:"functionDeclarations"`
		} `json:"tools"`
	}
	if err := json.Unmarshal(gotBody, &got); err != nil {
		t.Fatalf("body invalido: %v\n%s", err, gotBody)
	}
	if len(got.SystemInstruction.Parts) == 0 || got.SystemInstruction.Parts[0].Text != "voce e um analista" {
		t.Fatalf("system_instruction errado: %+v", got.SystemInstruction)
	}
	if len(got.Tools) != 1 || len(got.Tools[0].FunctionDeclarations) != 1 ||
		got.Tools[0].FunctionDeclarations[0].Name != "triage" {
		t.Fatalf("functionDeclarations erradas: %+v", got.Tools)
	}
	// user, model(functionCall), user(functionResponse)
	if len(got.Contents) != 3 {
		t.Fatalf("esperava 3 contents, veio %d", len(got.Contents))
	}
	if got.Contents[1].Role != "model" || !strings.Contains(string(got.Contents[1].Parts), "functionCall") {
		t.Fatalf("turno do modelo deveria ter functionCall: %s", got.Contents[1].Parts)
	}
	if got.Contents[2].Role != "user" || !strings.Contains(string(got.Contents[2].Parts), "functionResponse") ||
		!strings.Contains(string(got.Contents[2].Parts), "triage") {
		t.Fatalf("functionResponse deveria referenciar triage: %s", got.Contents[2].Parts)
	}
}

func TestGeminiParsesFinalText(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(w, `{"candidates":[{"content":{"parts":[{"text":"{\"causa_raiz\":\"y\"}"}]}}]}`)
	}))
	defer srv.Close()

	p := NewGemini("k", "")
	p.baseURL = srv.URL

	resp, err := p.Generate([]agent.Message{{Role: agent.RoleUser, Content: "oi"}}, nil)
	if err != nil {
		t.Fatal(err)
	}
	if resp.WantsTools() || resp.FinalText != `{"causa_raiz":"y"}` {
		t.Fatalf("esperava texto final, veio %+v", resp)
	}
}
