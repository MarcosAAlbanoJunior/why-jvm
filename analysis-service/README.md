# analysis-service (Go)

O lado **Go** do split (Fase 5): recebe o `IncidentRecord` JSON que o lado Java
extrai do JFR, persiste e — nas próximas fases — serve as tools MCP, roda o loop
do agente (BYO-LLM) e despacha o laudo pros canais.

> Contrato e racional: [`../GO-ANALYSIS-SERVICE.md`](../GO-ANALYSIS-SERVICE.md).
> Schema canônico que ele consome: [`../schema/`](../schema/).
> Ordem de execução: [`../ROADMAP.md`](../ROADMAP.md).

**Princípio:** o Go **nunca parseia JFR**. Só consome o JSON de agregados já
mastigado pelo Java. Por isso o binário é leve e sobrevive ao OOM do app.

## Status: B1 — ingest + store

Implementado nesta fase:

- `POST /v1/incidents` — recebe o `IncidentRecord` JSON, autentica (bearer),
  valida (`schemaVersion`, campos-chave), persiste **idempotente** por
  `incidentId` e responde `202`.
- `GET /healthz` — liveness/readiness.
- Store em disco **lossless** (guarda os bytes JSON crus), idempotente.

Pendente (próximas fases): tools MCP como leitores do JSON (B2), loop do agente
BYO-LLM (B3), sinks/canais (B4), store em Postgres.

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

## Layout

```
cmd/analysis-service/   # main: lê config, sobe o servidor
internal/incident/      # Record + nested (structs = schema v1)
internal/store/         # Store interface + FileStore (disco, idempotente)
internal/api/           # ingest + healthz (ServeMux, routing por método)
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
