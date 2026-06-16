package agent

import (
	"fmt"
	"regexp"
	"strings"
)

var incidentIDRe = regexp.MustCompile(`incidentId=(\S+)`)

// Stub e um provider deterministico, sem key, para fechar o circuito sem LLM e
// para testes. Imita o caminho minimo de um agente real: triage primeiro, depois
// drill-down dirigido pela triagem, e por fim um laudo JSON. Porta o
// StubLlmProvider do core. Troque por um provider real implementando Provider.
type Stub struct{}

// NewStub cria o provider stub.
func NewStub() Stub { return Stub{} }

// Name implementa Provider.
func (Stub) Name() string { return "stub" }

// Generate implementa Provider.
func (Stub) Generate(context []Message, tools []ToolSpec) (Response, error) {
	incidentID := extractIncidentID(context)

	// 1) Sempre comeca por triage, se disponivel.
	if available(tools, "triage") && !alreadyCalled(context, "triage") {
		return toolCallResponse("call-triage", "triage", incidentID), nil
	}

	// 2) Drill-down dirigido: segue o "Proximo passo sugerido" da triagem.
	//    Sem triagem, cai no get_exception_details (circuito da Fase 0).
	drill := suggestedNextTool(context, tools)
	if drill == "" && available(tools, "get_exception_details") {
		drill = "get_exception_details"
	}
	if drill != "" && !alreadyCalled(context, drill) {
		return toolCallResponse("call-drill", drill, incidentID), nil
	}

	// 3) Evidencia suficiente: monta o laudo a partir do ultimo agregado lido.
	firstLine := firstNonBlank(lastToolContent(context))
	laudo := fmt.Sprintf(`{
  "causa_raiz": "Diagnostico requer um provider LLM real; o stub apenas coletou a evidencia abaixo (sem analise).",
  "evidencia": ["%s"],
  "confianca": "baixa",
  "correcao_sugerida": "Substituir o StubProvider por um provider LLM real para analise."
}`, strings.ReplaceAll(firstLine, `"`, "'"))
	return Response{FinalText: laudo}, nil
}

func toolCallResponse(id, name, incidentID string) Response {
	return Response{ToolCalls: []ToolCall{{
		ID:        id,
		Name:      name,
		Arguments: map[string]any{"incidentId": incidentID},
	}}}
}

func available(tools []ToolSpec, name string) bool {
	for _, t := range tools {
		if t.Name == name {
			return true
		}
	}
	return false
}

func alreadyCalled(context []Message, toolName string) bool {
	for _, m := range context {
		if m.Role != RoleAssistant {
			continue
		}
		for _, c := range m.ToolCalls {
			if c.Name == toolName {
				return true
			}
		}
	}
	return false
}

// suggestedNextTool le o "Proximo passo sugerido: X" da triagem e devolve X se
// for uma tool registrada.
func suggestedNextTool(context []Message, tools []ToolSpec) string {
	const marker = "Proximo passo sugerido:"
	for _, m := range context {
		if m.Role != RoleTool || m.Content == "" {
			continue
		}
		for _, line := range strings.Split(m.Content, "\n") {
			s := strings.TrimSpace(line)
			if !strings.HasPrefix(s, marker) {
				continue
			}
			token := strings.TrimSpace(strings.TrimPrefix(s, marker))
			if i := strings.IndexByte(token, ' '); i >= 0 {
				token = token[:i]
			}
			if available(tools, token) {
				return token
			}
		}
	}
	return ""
}

func lastToolContent(context []Message) string {
	last := ""
	for _, m := range context {
		if m.Role == RoleTool {
			last = m.Content
		}
	}
	return last
}

func firstNonBlank(s string) string {
	for _, line := range strings.Split(s, "\n") {
		if strings.TrimSpace(line) != "" {
			return line
		}
	}
	return s
}

func extractIncidentID(context []Message) string {
	for _, m := range context {
		if match := incidentIDRe.FindStringSubmatch(m.Content); match != nil {
			return match[1]
		}
	}
	return "unknown"
}
