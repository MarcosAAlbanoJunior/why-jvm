# why-jvm-mcp

Root Cause Analysis automático para aplicações Java, acionado por gatilho. Quando
um endpoint dá erro (ou, nas próximas fases, fica lento), um gatilho determinístico
**congela** uma janela de evidências (JFR + OpenTelemetry) e dispara um agente de IA
que investiga e explica, em linguagem natural, o que aconteceu e por quê.

A premissa: a IA nunca lê o firehose. Ela só roda quando algo quebra, e mesmo assim
só enxerga agregados do incidente. Isso é o que torna o custo de token viável.

> Visão completa e racional do design: [INSTRUCTION.md](INSTRUCTION.md).
> Como plugar num app real (e o caminho para `-javaagent` zero-código): [INTEGRATION.md](INTEGRATION.md).
> Contrato do split: o que o serviço de análise em Go recebe do Java e faz: [GO-ANALYSIS-SERVICE.md](GO-ANALYSIS-SERVICE.md).
> Ordem concreta de execução do split (Fase 5 + 5.5): [ROADMAP.md](ROADMAP.md).

## Módulos

| Módulo | O que é |
|---|---|
| [`core/`](core/) | A biblioteca — o produto. Gatilho, captura, tools MCP, agente, sink. JAR puro, sem Spring. |
| [`sinks/`](sinks/) | Sinks reais que puxam dependência de transporte (e-mail via Jakarta Mail hoje; Slack/WhatsApp depois). Fora do `core` para mantê-lo puro. |
| [`llm/`](llm/) | Provider de IA real (`LangChain4jProvider`) — Gemini via LangChain4j, multi-provider por env. Também fora do `core`. |
| [`sample-app/`](sample-app/) | App Spring Boot de teste. Existe só para gerar spans reais e exercitar o circuito. Não é o produto. |

## Arquitetura (pacotes do `core`)

```
SpanProcessor.onEnd ──► TriggerService ──► EvidenceCapture (JFR takeSnapshot, SÍNCRONO)
   (trigger/)              (trigger/)          (capture/)
                                                   │ grava IncidentRecord
                                                   ▼
                              AgentLoop ◄──── McpToolRegistry (tools, devolvem AGREGADOS)
                              (agent/)            (mcp/)
                                 │ Laudo estruturado
                                 ▼
                               Sink  (log / Slack / WhatsApp)
                              (sink/)
```

As duas **fronteiras de extensão** do modo autônomo:

- **`LlmProvider`** (`agent/`) — qual IA. BYOK: a implementação lê a própria key do
  ambiente; o núcleo nunca guarda credencial. Default: `StubLlmProvider` (sem key).
  Provider real no módulo [`llm/`](llm/): `LangChain4jProvider` (Gemini hoje;
  Claude/OpenAI/Ollama trocando dependência + env, ver `LlmProviders.fromEnv()`).
- **`Sink`** (`sink/`) — onde o laudo é postado. Default: `LoggingSink`; e-mail no
  módulo [`sinks/`](sinks/).

Trocam-se via `WhyJvm.builder().llmProvider(...).sink(...)`.

## Rodando o circuito da Fase 0

```bash
./gradlew :sample-app:bootRun
# em outro terminal:
curl http://localhost:8080/demo/error
```

O `/demo/error` lança uma exception → o gatilho dispara → JFR congela a janela →
o agente (stub) monta um laudo → o sink imprime no log. O snapshot `.jfr` fica em
`incidents/`.

## Onde cada fase do roadmap mora no código

| Fase | Entrega | Status no código |
|---|---|---|
| 0 | Circuito fechado: erro → captura → `get_exception_details` → laudo | ✅ esqueleto pronto |
| 1 | Fingerprint, dedup e cooldown (controle de tempestade) | ✅ `Fingerprints` + `IncidentDeduplicator` |
| 2 | `triage` determinística (correlação latência×GC×lock) | ✅ `TriageTool` (correlação JFR vem na Fase 3) |
| 3 | Baseline de lentidão + tools de GC/alocação/lock | 🟢 baseline + tools JFR (GC/aloc/lock) + triagem correlacionada; falta `get_slow_traces` (captura da árvore do trace) |
| 4 | Sinks reais (e-mail, Slack, WhatsApp) | 🟢 `EmailSink` (módulo `sinks/`, SMTP via env); Slack/WhatsApp pendentes |
| 5 | Split MCP por HTTP (SDK `io.modelcontextprotocol.sdk:mcp`), agente em Go | 📄 contrato em [GO-ANALYSIS-SERVICE.md](GO-ANALYSIS-SERVICE.md) |
| 5.5 | Production-hardening: dump assíncrono + single-flight, dedup/baseline compartilhado (Redis), JFR config tunado | ⬜ planejado (ver INSTRUCTION §10) |

## Requisitos

- JDK 21 (LTS). O `jdk.ObjectAllocationSample` (Fase 3) precisa de JDK 16+.
- O Gradle vem pelo wrapper (`./gradlew`), não precisa instalar.
