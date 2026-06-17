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
Para incidentes SLOW, get_thread_activity e OBRIGATORIO e vem ANTES de
qualquer dimensao. Ele e a UNICA tool que filtra pela thread do request;
get_gc_activity e get_allocation_hotspots somam a JVM INTEIRA (todas as
threads) e frequentemente mostram ruido de fundo — alocacao de JIT/compilacao
e de instrumentacao (ex.: ByteVector/ASM), GC disparado por outras requests.
Se a thread do request passou o tempo ESPERANDO (sleep, I/O, lock, park), a
causa e EXTERNA (banco, downstream, I/O) e voce NAO deve culpar GC/alocacao
mesmo que os numeros JVM-wide sejam altos — eles sao de outras threads; diga
que e espera externa e que nenhuma dimensao JVM e culpada. So investigue
alocacao/GC se a thread do request esteve majoritariamente em CPU.
Calibre a confianca pela fracao da latencia explicada E pela atribuicao a
thread do request: nao diga 'alta' se a maior parte da latencia ficou sem
explicacao na thread do request, nem com base so em numeros JVM-wide.
Quando a thread do request ESPEROU (espera externa) ou a triagem indicar
sub-spans no trace, chame get_slow_traces ANTES de concluir: ele mostra QUAL
chamada (banco/downstream) dominou a latencia e detecta N+1 (muitas chamadas
identicas). Se houver N+1, a causa raiz e a REPETICAO (ex.: 40x a mesma query
'SELECT orders'), nao uma chamada isolada nem 'park/espera' generico — diga
isso explicitamente e quantifique. Nao conclua 'espera externa' sem antes
olhar o trace.
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
			laudo := parseLaudo(resp.FinalText, rec)
			laudo.HipotesesDescartadas = differential(rec) // diferencial deterministico
			laudo.CodeContext = rec.CodeContext            // fonte do metodo suspeito (Tier 2)
			return laudo, nil
		}
		if used >= l.maxToolCalls {
			laudo := turnLimitLaudo(rec)
			laudo.HipotesesDescartadas = differential(rec)
			laudo.CodeContext = rec.CodeContext
			return laudo, nil
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
