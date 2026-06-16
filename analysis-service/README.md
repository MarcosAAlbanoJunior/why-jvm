# analysis-service (Go)

O lado **Go** do split (Fase 5): recebe o `IncidentRecord` JSON que o lado Java
extrai do JFR, persiste e — nas próximas fases — serve as tools MCP, roda o loop
do agente (BYO-LLM) e despacha o laudo pros canais.

> Contrato e racional: [`../GO-ANALYSIS-SERVICE.md`](../GO-ANALYSIS-SERVICE.md).
> Schema canônico que ele consome: [`../schema/`](../schema/).
> Ordem de execução: [`../ROADMAP.md`](../ROADMAP.md).

**Princípio:** o Go **nunca parseia JFR**. Só consome o JSON de agregados já
mastigado pelo Java. Por isso o binário é leve e sobrevive ao OOM do app.

## Status: B1 (ingest + store), B2 (tools), B3 (loop do agente) + providers reais

Implementado:

- `POST /v1/incidents` — recebe o `IncidentRecord` JSON, autentica (bearer),
  valida (`schemaVersion`, campos-chave), persiste **idempotente** por
  `incidentId` e responde `202`.
- `GET /healthz` — liveness/readiness.
- Store em disco **lossless** (guarda os bytes JSON crus), idempotente.
- **Tools como leitores finos** do JSON (B2): catálogo espelhando o `core`
  (`triage`, `get_exception_details`, `get_thread_activity`, `get_gc_activity`,
  `get_allocation_hotspots`, `get_lock_contention`, `get_endpoint_baseline`). O
  texto é portado das tools Java, para o agente receber o contexto consistente.
  Expostas via HTTP (modo interativo); o agente chama o `tools.Registry` in-process.
- **Loop do agente** (B3): function calling sobre o catálogo até convergir num
  `Laudo` estruturado, com teto de tool calls (anti-loop de token). Porta o
  `AgentLoop` do `core`.
- **Providers reais BYO-LLM** (`internal/llm`): clientes HTTP diretos (sem SDK)
  pra **Claude** (`/v1/messages`, model `claude-opus-4-8`) e **Gemini**
  (`generateContent`, model `gemini-2.5-flash`), selecionados por env via
  `FromEnv`. Default: `Stub` determinístico (sem key). A key vem do ambiente — o
  serviço nunca a guarda.
- **Modo autônomo** (B4): ao receber um incidente, um worker **single-thread**
  investiga sozinho (bounded — um por vez, custo/rate-limit do LLM) e despacha o
  laudo num `Sink`. `LogSink` hoje (laudo no log); Slack/e-mail/WhatsApp depois.
  Liga/desliga por `WHYJVM_AUTO_INVESTIGATE` (default ligado).

| Método + rota | O que faz |
|---|---|
| `POST /v1/incidents` | Ingere um incidente (auth, idempotente) → `202`. No modo autônomo, dispara a investigação async |
| `GET /v1/incidents/{id}` | JSON cru do incidente persistido |
| `GET /v1/incidents/{id}/tools/{tool}` | Roda uma tool e devolve o agregado em texto |
| `POST /v1/incidents/{id}/investigate` | Roda o loop do agente → `Laudo` JSON |
| `GET /v1/tools` | Catálogo de tools (nome + descrição) |
| `GET /healthz` | Liveness/readiness |

Pendente (próximas fases): provider LLM real (BYO-LLM), sinks/canais (B4),
investigação automática no ingest, servidor MCP stdio/HTTP, store em Postgres.

## Rodar

```bash
cd analysis-service
WHYJVM_INGEST_TOKEN=segredo WHYJVM_STORE_DIR=incidents-store \
  go run ./cmd/analysis-service
# noutro terminal:
curl -i -X POST http://localhost:8080/v1/incidents \
  -H "Authorization: Bearer segredo" \
  -H "Content-Type: application/json" \
  --data-binary @../schema/fixtures/incident-slow-gc.json
```

## Configuração (env)

| Variável | Default | O que é |
|---|---|---|
| `WHYJVM_ADDR` | `:8080` | Endereço de escuta. |
| `WHYJVM_INGEST_TOKEN` | _(vazio)_ | Bearer exigido no ingest. **Vazio = sem auth (só dev).** |
| `WHYJVM_STORE_DIR` | `incidents-store` | Diretório do store em disco. |
| `WHYJVM_LLM_PROVIDER` | `stub` | Provider do agente: `stub` \| `claude` \| `gemini`. |
| `WHYJVM_LLM_MODEL` | _(default do provider)_ | Override do model (`claude-opus-4-8` / `gemini-2.5-flash`). |
| `ANTHROPIC_API_KEY` | _(vazio)_ | Key do Claude (BYOK), exigida se `WHYJVM_LLM_PROVIDER=claude`. |
| `GEMINI_API_KEY` | _(vazio)_ | Key do Gemini (BYOK), exigida se `WHYJVM_LLM_PROVIDER=gemini`. |
| `WHYJVM_AUTO_INVESTIGATE` | `true` | Modo autônomo: investiga ao receber o incidente. `false` = só `/investigate` manual. |

## Layout

```
cmd/analysis-service/   # main: lê config, sobe o servidor
internal/incident/      # Record + nested (structs = schema v1)
internal/store/         # Store interface + FileStore (disco, idempotente)
internal/tools/         # catálogo de tools: leitores finos do JSON (texto)
internal/agent/         # loop de function calling + Provider (Stub) + Laudo
internal/llm/           # providers reais BYO-LLM: Claude + Gemini (HTTP) + FromEnv
internal/sink/          # destino do laudo: Sink + LogSink (Slack/e-mail depois)
internal/api/           # ingest + leitura/tools + investigate + worker autônomo
internal/config/        # config por env
```

## Testes

```bash
go test ./...
```

`internal/api/ingest_test.go` carrega as **fixtures reais** de `../schema/fixtures`
e valida o circuito (202, idempotência, 401 token errado, 400 schemaVersion/JSON
inválido) — fechando o consumidor contra o mesmo contrato congelado no M0.

> Nota: só stdlib (sem dependências externas). O caminho do módulo em `go.mod`
> (`github.com/whyjvm/analysis-service`) é placeholder até publicar.
