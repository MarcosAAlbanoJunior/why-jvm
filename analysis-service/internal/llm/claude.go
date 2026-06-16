package llm

import (
	"errors"
	"net/http"
	"strings"

	"github.com/whyjvm/analysis-service/internal/agent"
)

// DefaultClaudeModel e o model id default (ver referencia da Claude API).
const DefaultClaudeModel = "claude-opus-4-8"

const claudeAPIVersion = "2023-06-01"
const claudeMaxTokens = 8192

// Claude e um provider sobre a API de Messages da Anthropic (/v1/messages),
// cliente HTTP direto. BYOK: a key vem do ambiente.
type Claude struct {
	apiKey  string
	model   string
	baseURL string
	http    *http.Client
}

// NewClaude monta o provider. baseURL default e a API publica; testes injetam.
func NewClaude(apiKey, model string) *Claude {
	if model == "" {
		model = DefaultClaudeModel
	}
	return &Claude{
		apiKey:  apiKey,
		model:   model,
		baseURL: "https://api.anthropic.com",
		http:    &http.Client{Timeout: defaultTimeout},
	}
}

// Name implementa agent.Provider.
func (c *Claude) Name() string { return "claude" }

// --- wire types ---

type claudeRequest struct {
	Model     string          `json:"model"`
	MaxTokens int             `json:"max_tokens"`
	System    string          `json:"system,omitempty"`
	Tools     []claudeTool    `json:"tools,omitempty"`
	Messages  []claudeMessage `json:"messages"`
}

type claudeTool struct {
	Name        string         `json:"name"`
	Description string         `json:"description"`
	InputSchema map[string]any `json:"input_schema"`
}

type claudeMessage struct {
	Role    string `json:"role"`
	Content any    `json:"content"` // string ou []claudeBlock
}

type claudeBlock struct {
	Type      string         `json:"type"`
	Text      string         `json:"text,omitempty"`
	ID        string         `json:"id,omitempty"`
	Name      string         `json:"name,omitempty"`
	Input     map[string]any `json:"input,omitempty"`
	ToolUseID string         `json:"tool_use_id,omitempty"`
	Content   string         `json:"content,omitempty"`
}

type claudeResponse struct {
	StopReason string `json:"stop_reason"`
	Content    []struct {
		Type  string         `json:"type"`
		Text  string         `json:"text"`
		ID    string         `json:"id"`
		Name  string         `json:"name"`
		Input map[string]any `json:"input"`
	} `json:"content"`
	Error *struct {
		Message string `json:"message"`
	} `json:"error"`
}

// Generate implementa agent.Provider.
func (c *Claude) Generate(context []agent.Message, tools []agent.ToolSpec) (agent.Response, error) {
	req := claudeRequest{
		Model:     c.model,
		MaxTokens: claudeMaxTokens,
		System:    systemText(context),
		Tools:     claudeTools(tools),
		Messages:  claudeMessages(context),
	}
	var resp claudeResponse
	err := postJSON(c.http, c.baseURL+"/v1/messages", map[string]string{
		"x-api-key":         c.apiKey,
		"anthropic-version": claudeAPIVersion,
	}, req, &resp)
	if err != nil {
		return agent.Response{}, err
	}
	if resp.Error != nil {
		return agent.Response{}, errors.New("claude: " + resp.Error.Message)
	}
	if resp.StopReason == "refusal" {
		return agent.Response{}, errors.New("claude recusou a requisicao (stop_reason=refusal)")
	}

	var out agent.Response
	var text strings.Builder
	for _, b := range resp.Content {
		switch b.Type {
		case "tool_use":
			out.ToolCalls = append(out.ToolCalls, agent.ToolCall{ID: b.ID, Name: b.Name, Arguments: b.Input})
		case "text":
			text.WriteString(b.Text)
		}
	}
	if !out.WantsTools() {
		out.FinalText = text.String()
	}
	return out, nil
}

func claudeTools(tools []agent.ToolSpec) []claudeTool {
	out := make([]claudeTool, 0, len(tools))
	for _, t := range tools {
		out = append(out, claudeTool{Name: t.Name, Description: t.Description, InputSchema: t.InputSchema})
	}
	return out
}

// claudeMessages mapeia o contexto neutro para o formato da Anthropic. Junta
// resultados de tool consecutivos num unico turno de user (um tool_result por
// bloco), como a API espera apos um turno de assistant com tool_use.
func claudeMessages(context []agent.Message) []claudeMessage {
	var msgs []claudeMessage
	var ns []agent.Message
	for _, m := range context {
		if m.Role != agent.RoleSystem {
			ns = append(ns, m)
		}
	}
	for i := 0; i < len(ns); {
		m := ns[i]
		switch m.Role {
		case agent.RoleUser:
			msgs = append(msgs, claudeMessage{Role: "user", Content: m.Content})
			i++
		case agent.RoleAssistant:
			if len(m.ToolCalls) == 0 {
				msgs = append(msgs, claudeMessage{Role: "assistant", Content: m.Content})
				i++
				continue
			}
			var blocks []claudeBlock
			if m.Content != "" {
				blocks = append(blocks, claudeBlock{Type: "text", Text: m.Content})
			}
			for _, call := range m.ToolCalls {
				blocks = append(blocks, claudeBlock{Type: "tool_use", ID: call.ID, Name: call.Name, Input: call.Arguments})
			}
			msgs = append(msgs, claudeMessage{Role: "assistant", Content: blocks})
			i++
		case agent.RoleTool:
			var results []claudeBlock
			for i < len(ns) && ns[i].Role == agent.RoleTool {
				t := ns[i]
				id := ""
				if t.ToolResultFor != nil {
					id = t.ToolResultFor.ID
				}
				results = append(results, claudeBlock{Type: "tool_result", ToolUseID: id, Content: t.Content})
				i++
			}
			msgs = append(msgs, claudeMessage{Role: "user", Content: results})
		default:
			i++
		}
	}
	return msgs
}
