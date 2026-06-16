package agent

import (
	"encoding/json"
	"fmt"
	"strings"

	"github.com/whyjvm/analysis-service/internal/incident"
	"github.com/whyjvm/analysis-service/internal/tools"
)

const defaultMaxToolCalls = 8

const systemPrompt = `Voce e um analista de causa raiz de JVM. Um incidente disparou.
Investigue usando as ferramentas disponiveis. Comece sempre por triage
quando existir. Faca drill-down apenas na dimensao que a triagem apontar
como suspeita. Nao chame ferramentas de dimensoes irrelevantes.
Numeros pequenos sao ruido de fundo: toda JVM sempre tem alguma alocacao
e algum GC. So atribua a causa a uma dimensao se a magnitude for
claramente significativa frente a latencia do incidente. Se a latencia e
alta mas nenhuma dimensao JVM tem sinal relevante (sem exception, GC
pequeno, alocacao baixa, sem lock), a causa provavel e EXTERNA a JVM —
espera de I/O, query de banco ou chamada downstream; diga isso e nao
culpe uma alocacao trivial. Calibre a confianca pela forca da evidencia:
alta so com evidencia forte e consistente.
Em incidentes lentos, comece o drill por get_thread_activity: ele diz se
a thread do request esperou (sleep/I/O/lock = causa externa) ou trabalhou
(CPU = investigar algoritmo/alocacao). So depois olhe GC/alocacao.
Ao concluir, produza um laudo JSON com os campos: endpoint, tipo,
causa_raiz, evidencia (lista), confianca (alta/media/baixa) e
correcao_sugerida. Responda APENAS com o JSON cru, sem cercas de
markdown e sem texto antes ou depois.`

// Loop e o loop de function calling. Investiga -> hipotese -> drill, ate
// convergir num laudo, com teto de chamadas de tool para uma alucinacao nao
// virar loop infinito de token. Porta o AgentLoop do core.
type Loop struct {
	provider     Provider
	registry     *tools.Registry
	maxToolCalls int
}

// NewLoop monta o loop. maxToolCalls <= 0 usa o default.
func NewLoop(provider Provider, registry *tools.Registry, maxToolCalls int) *Loop {
	if maxToolCalls <= 0 {
		maxToolCalls = defaultMaxToolCalls
	}
	return &Loop{provider: provider, registry: registry, maxToolCalls: maxToolCalls}
}

// Investigate roda o loop sobre um incidente e devolve o laudo.
func (l *Loop) Investigate(rec *incident.Record) (Laudo, error) {
	ctx := []Message{
		systemMsg(systemPrompt),
		userMsg(initialContext(rec)),
	}
	specs := l.toolSpecs()

	used := 0
	for {
		resp, err := l.provider.Generate(ctx, specs)
		if err != nil {
			return Laudo{}, fmt.Errorf("provider %s falhou: %w", l.provider.Name(), err)
		}
		if !resp.WantsTools() {
			return parseLaudo(resp.FinalText, rec), nil
		}
		if used >= l.maxToolCalls {
			return turnLimitLaudo(rec), nil
		}
		ctx = append(ctx, assistantToolCalls(resp.ToolCalls))
		for _, call := range resp.ToolCalls {
			out, err := l.registry.Call(rec.IncidentID, call.Name)
			if err != nil {
				out = "erro ao executar " + call.Name + ": " + err.Error()
			}
			ctx = append(ctx, toolResult(call, out))
			used++
		}
	}
}

func (l *Loop) toolSpecs() []ToolSpec {
	catalog := l.registry.List()
	specs := make([]ToolSpec, 0, len(catalog))
	for _, t := range catalog {
		specs = append(specs, ToolSpec{
			Name:        t.Name,
			Description: t.Description,
			InputSchema: incidentIDSchema(),
		})
	}
	return specs
}

// incidentIDSchema e o schema de entrada comum a todas as tools: so o incidentId.
// O loop e por incidente, entao a execucao usa sempre o id do incidente corrente,
// independentemente do que o modelo passe nos argumentos.
func incidentIDSchema() map[string]any {
	return map[string]any{
		"type": "object",
		"properties": map[string]any{
			"incidentId": map[string]any{
				"type":        "string",
				"description": "Id do incidente a investigar.",
			},
		},
		"required": []string{"incidentId"},
	}
}

func initialContext(rec *incident.Record) string {
	return fmt.Sprintf("incidentId=%s\nTipo: %s\nEndpoint: %s\nLatencia: %dms\n",
		rec.IncidentID, rec.Type, rec.Endpoint, rec.DurationMs)
}

// laudoJSON e a forma de leitura do laudo emitido pelo modelo (campos opcionais).
type laudoJSON struct {
	Endpoint         *string  `json:"endpoint"`
	Tipo             *string  `json:"tipo"`
	CausaRaiz        *string  `json:"causa_raiz"`
	Evidencia        []string `json:"evidencia"`
	Confianca        *string  `json:"confianca"`
	CorrecaoSugerida *string  `json:"correcao_sugerida"`
}

// parseLaudo extrai o laudo do texto do modelo, com fallbacks do incidente. Se
// nao for JSON valido, embrulha o texto cru como causa_raiz (confianca baixa).
func parseLaudo(text string, rec *incident.Record) Laudo {
	out := Laudo{
		Endpoint:  rec.Endpoint,
		Tipo:      rec.Type,
		CausaRaiz: "indeterminada",
		Evidencia: []string{},
		Confianca: "baixa",
	}
	var p laudoJSON
	if err := json.Unmarshal([]byte(extractJSON(text)), &p); err != nil {
		out.CausaRaiz = text
		return out
	}
	if p.Endpoint != nil {
		out.Endpoint = *p.Endpoint
	}
	if p.Tipo != nil {
		out.Tipo = *p.Tipo
	}
	if p.CausaRaiz != nil {
		out.CausaRaiz = *p.CausaRaiz
	}
	if p.Evidencia != nil {
		out.Evidencia = p.Evidencia
	}
	if p.Confianca != nil {
		out.Confianca = *p.Confianca
	}
	if p.CorrecaoSugerida != nil {
		out.CorrecaoSugerida = *p.CorrecaoSugerida
	}
	return out
}

// extractJSON pega do primeiro '{' ao ultimo '}' — modelos costumam cercar o
// JSON em ```json ... ``` ou por texto. Porta AgentLoop.extractJson.
func extractJSON(text string) string {
	start := strings.IndexByte(text, '{')
	end := strings.LastIndexByte(text, '}')
	if start >= 0 && end > start {
		return text[start : end+1]
	}
	return text
}

func turnLimitLaudo(rec *incident.Record) Laudo {
	return Laudo{
		Endpoint:         rec.Endpoint,
		Tipo:             rec.Type,
		CausaRaiz:        "Investigacao interrompida pelo teto de turnos.",
		Evidencia:        []string{"Limite de chamadas de tool atingido antes de convergir."},
		Confianca:        "baixa",
		CorrecaoSugerida: "Revisar manualmente ou aumentar o teto de turnos.",
	}
}
