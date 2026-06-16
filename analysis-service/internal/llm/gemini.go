package llm

import (
	"errors"
	"net/http"
	"strings"

	"github.com/whyjvm/analysis-service/internal/agent"
)

// DefaultGeminiModel espelha o default do lado Java (LlmProviders.fromEnv).
const DefaultGeminiModel = "gemini-2.5-flash"

const geminiMaxTokens = 8192

// Gemini e um provider sobre o generateContent da Google AI API, cliente HTTP
// direto. BYOK: a key vem do ambiente.
type Gemini struct {
	apiKey  string
	model   string
	baseURL string
	http    *http.Client
}

// NewGemini monta o provider. baseURL default e a API publica; testes injetam.
func NewGemini(apiKey, model string) *Gemini {
	if model == "" {
		model = DefaultGeminiModel
	}
	return &Gemini{
		apiKey:  apiKey,
		model:   model,
		baseURL: "https://generativelanguage.googleapis.com",
		http:    &http.Client{Timeout: defaultTimeout},
	}
}

// Name implementa agent.Provider.
func (g *Gemini) Name() string { return "gemini" }

// --- wire types ---

type geminiRequest struct {
	SystemInstruction *geminiContent   `json:"system_instruction,omitempty"`
	Contents          []geminiContent  `json:"contents"`
	Tools             []geminiTool     `json:"tools,omitempty"`
	GenerationConfig  *geminiGenConfig `json:"generationConfig,omitempty"`
}

type geminiContent struct {
	Role  string       `json:"role,omitempty"`
	Parts []geminiPart `json:"parts"`
}

type geminiPart struct {
	Text             string                  `json:"text,omitempty"`
	FunctionCall     *geminiFunctionCall     `json:"functionCall,omitempty"`
	FunctionResponse *geminiFunctionResponse `json:"functionResponse,omitempty"`
}

type geminiFunctionCall struct {
	Name string         `json:"name"`
	ID   string         `json:"id,omitempty"`
	Args map[string]any `json:"args,omitempty"`
}

type geminiFunctionResponse struct {
	Name     string         `json:"name"`
	ID       string         `json:"id,omitempty"`
	Response map[string]any `json:"response"`
}

type geminiTool struct {
	FunctionDeclarations []geminiFunctionDeclaration `json:"functionDeclarations"`
}

type geminiFunctionDeclaration struct {
	Name        string         `json:"name"`
	Description string         `json:"description"`
	Parameters  map[string]any `json:"parameters,omitempty"`
}

type geminiGenConfig struct {
	MaxOutputTokens int `json:"maxOutputTokens,omitempty"`
}

type geminiResponse struct {
	Candidates []struct {
		Content geminiContent `json:"content"`
	} `json:"candidates"`
	Error *struct {
		Message string `json:"message"`
	} `json:"error"`
}

// Generate implementa agent.Provider.
func (g *Gemini) Generate(context []agent.Message, tools []agent.ToolSpec) (agent.Response, error) {
	req := geminiRequest{
		Contents:         geminiContents(context),
		Tools:            geminiTools(tools),
		GenerationConfig: &geminiGenConfig{MaxOutputTokens: geminiMaxTokens},
	}
	if sys := systemText(context); sys != "" {
		req.SystemInstruction = &geminiContent{Parts: []geminiPart{{Text: sys}}}
	}

	url := g.baseURL + "/v1beta/models/" + g.model + ":generateContent"
	var resp geminiResponse
	if err := postJSON(g.http, url, map[string]string{"x-goog-api-key": g.apiKey}, req, &resp); err != nil {
		return agent.Response{}, err
	}
	if resp.Error != nil {
		return agent.Response{}, errors.New("gemini: " + resp.Error.Message)
	}
	if len(resp.Candidates) == 0 {
		return agent.Response{}, errors.New("gemini: resposta sem candidates")
	}

	var out agent.Response
	var text strings.Builder
	for _, p := range resp.Candidates[0].Content.Parts {
		if p.FunctionCall != nil {
			out.ToolCalls = append(out.ToolCalls, agent.ToolCall{
				ID:        p.FunctionCall.ID,
				Name:      p.FunctionCall.Name,
				Arguments: p.FunctionCall.Args,
			})
		} else if p.Text != "" {
			text.WriteString(p.Text)
		}
	}
	if !out.WantsTools() {
		out.FinalText = text.String()
	}
	return out, nil
}

func geminiTools(tools []agent.ToolSpec) []geminiTool {
	if len(tools) == 0 {
		return nil
	}
	decls := make([]geminiFunctionDeclaration, 0, len(tools))
	for _, t := range tools {
		decls = append(decls, geminiFunctionDeclaration{
			Name:        t.Name,
			Description: t.Description,
			Parameters:  t.InputSchema,
		})
	}
	return []geminiTool{{FunctionDeclarations: decls}}
}

// geminiContents mapeia o contexto neutro para contents/parts. Assistant -> role
// "model" (com functionCall parts); resultado de tool -> role "user" com um
// functionResponse part (a Google espera a response como objeto).
func geminiContents(context []agent.Message) []geminiContent {
	var contents []geminiContent
	for _, m := range context {
		switch m.Role {
		case agent.RoleUser:
			contents = append(contents, geminiContent{Role: "user", Parts: []geminiPart{{Text: m.Content}}})
		case agent.RoleAssistant:
			var parts []geminiPart
			if m.Content != "" {
				parts = append(parts, geminiPart{Text: m.Content})
			}
			for _, call := range m.ToolCalls {
				parts = append(parts, geminiPart{FunctionCall: &geminiFunctionCall{
					Name: call.Name, ID: call.ID, Args: call.Arguments,
				}})
			}
			contents = append(contents, geminiContent{Role: "model", Parts: parts})
		case agent.RoleTool:
			name, id := "", ""
			if m.ToolResultFor != nil {
				name, id = m.ToolResultFor.Name, m.ToolResultFor.ID
			}
			contents = append(contents, geminiContent{Role: "user", Parts: []geminiPart{{
				FunctionResponse: &geminiFunctionResponse{
					Name:     name,
					ID:       id,
					Response: map[string]any{"result": m.Content},
				},
			}}})
		}
	}
	return contents
}
