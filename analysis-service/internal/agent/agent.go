// Package agent roda o loop de function calling sobre o catalogo de tools. E o
// unico componente que gasta token, e so roda por incidente. Mantem o contrato
// do agent Java (Provider/Message/ToolCall/Laudo) para o comportamento ser
// consistente entre os dois lados do split.
package agent

// Role e o papel de uma mensagem no contexto.
type Role string

const (
	RoleSystem    Role = "system"
	RoleUser      Role = "user"
	RoleAssistant Role = "assistant"
	RoleTool      Role = "tool"
)

// Message e uma mensagem no contexto do agente. Formato neutro de provider: cada
// Provider traduz para o seu schema de function calling.
type Message struct {
	Role          Role
	Content       string
	ToolCalls     []ToolCall // turno de assistente que pede tools
	ToolResultFor *ToolCall  // resultado de tool, referenciando a chamada
}

// ToolCall e um pedido do modelo para executar uma tool.
type ToolCall struct {
	ID        string
	Name      string
	Arguments map[string]any
}

// ToolSpec descreve uma tool para o provider (nome + descricao).
type ToolSpec struct {
	Name        string
	Description string
}

// Response e a decisao de um turno: ou pede tools, ou entrega o texto final.
type Response struct {
	ToolCalls []ToolCall
	FinalText string
}

// WantsTools informa se o turno pediu execucao de tools.
func (r Response) WantsTools() bool {
	return len(r.ToolCalls) > 0
}

// Provider e a fronteira de IA (BYOK: le a propria key do ambiente). O loop e
// identico para Claude/Gemini/stub; cada provider so traduz contexto e tools.
type Provider interface {
	// Name identifica o provider em log/config (ex.: "stub", "gemini", "claude").
	Name() string
	// Generate roda um turno: recebe o contexto e o catalogo, devolve a decisao.
	Generate(context []Message, tools []ToolSpec) (Response, error)
}

// Laudo e a saida estruturada do agente. Os tags json batem com as chaves que o
// modelo emite (e que parseLaudo le), para rotear/armazenar de forma estavel.
type Laudo struct {
	Endpoint         string   `json:"endpoint"`
	Tipo             string   `json:"tipo"`
	CausaRaiz        string   `json:"causa_raiz"`
	Evidencia        []string `json:"evidencia"`
	Confianca        string   `json:"confianca"`
	CorrecaoSugerida string   `json:"correcao_sugerida"`
}

// Helpers de construcao de mensagem (espelham Message do core).

func systemMsg(content string) Message { return Message{Role: RoleSystem, Content: content} }
func userMsg(content string) Message    { return Message{Role: RoleUser, Content: content} }

func assistantToolCalls(calls []ToolCall) Message {
	return Message{Role: RoleAssistant, ToolCalls: calls}
}

func toolResult(call ToolCall, content string) Message {
	c := call
	return Message{Role: RoleTool, Content: content, ToolResultFor: &c}
}
