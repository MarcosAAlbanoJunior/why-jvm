# Roadmap — sequência de trabalho do split (Fase 5 + 5.5)

Ordem concreta de execução para tirar a investigação de dentro do app (split
Java→Go) e endurecer o caminho de captura para produção. As fases conceituais
moram no [INSTRUCTION.md §10](INSTRUCTION.md); o contrato do serviço Go mora no
[GO-ANALYSIS-SERVICE.md](GO-ANALYSIS-SERVICE.md). Este doc é só a **ordem**.

> Regra que organiza tudo: **o schema JSON é a espinha.** Java produz, Go consome.
> Congele o contrato primeiro e os dois lados andam em paralelo contra ele
> (o Go desenvolve com fixtures antes do Java emitir os reais). *Contract-first.*

---

## Caminho crítico vs. paralelo

```
M0 (contrato) ──┬──► TRACK A (Java)  A1 ─► A2 ──┐
                │                                ├──► M-int (ponta a ponta)
                └──► TRACK B (Go)   B1 ─► B2 ─► B3 ─► B4 ─┘
```

Depois de M0, os dois tracks rodam ao mesmo tempo e convergem no M-int.

---

## M0 — Congelar o schema (`IncidentRecord` v1) ✅

Antes de qualquer código. Travar o JSON da seção 2 do `GO-ANALYSIS-SERVICE.md`
como artefato versionado + 2-3 **fixtures** de exemplo (`ERROR`, `SLOW`-por-GC,
`SLOW`-por-lock).

- **Por que primeiro:** é a única superfície compartilhada entre Java e Go.
  Congelado, nenhum lado depende do outro para avançar.
- **Pronto quando:** existe `schema/incident-record.v1.json` + fixtures que os dois
  lados aceitam. ✅
- **Entregue:** [`schema/incident-record.v1.json`](schema/) (draft 2020-12) + 3
  fixtures validadas com ajv + [`schema/README.md`](schema/README.md) (regra de
  versionamento e como revalidar). Shapes fiéis às tools de `core`.

---

## Track A — Java (produz o JSON)

### A1 — `IncidentRecord` → agregados  *(+ Fase 5.5 #1)* ✅

Hoje o record carrega o **caminho** do `.jfr` e as tools parseiam o arquivo na hora
que o agente chama. Inverter: rodar a extração de **todas** as dimensões **uma vez,
logo após o snapshot**, e serializar o resultado no record (Jackson).

- **De-risca:** a lógica de parsing **já existe** nas tools atuais
  (`GetGcActivityTool`, `GetAllocationHotspotsTool`, `JfrCorrelation`…). A1 não é
  escrever parser novo — é **mudar quando ele roda** (eager, no capture) e montar o
  DTO.
- **Vale sozinho, antes do Go existir:** o agente in-process passa a ler agregados
  em vez de re-parsear `.jfr`; o *modo simples* continua funcionando. Colhe valor
  antes do split inteiro aterrissar.
- **Pronto quando:** o `IncidentRecord` serializa para o JSON do schema M0, e o
  modo in-process produz laudo lendo do record (não do `.jfr`). ✅
- **Entregue:** tipos de agregado em `io.whyjvm.capture` (espelham o schema);
  `EvidenceExtractor` (uma passada no JFR, folds puros testáveis); `IncidentRecord`
  carrega os agregados; tools leem do record; freeze síncrono + extração no executor
  single-thread (5.5#1); `IncidentRecordSchemaTest` valida a serialização contra o
  schema v1. Build multi-módulo verde.

### A2 — Durabilidade + transporte (padrão outbox)

Persistir o JSON local **antes** do POST; enviar ao Go por HTTP com retry/backoff a
partir do disco; idempotência por `incidentId`; tratar `202`.

- **Pronto quando:** com o serviço Go derrubado, gerar um incidente e subir o Go →
  o incidente chega (não se perdeu).

---

## Track B — Go (consome o JSON) — começa contra as fixtures do M0

### B1 — Ingest + store ✅

`POST /v1/incidents`, auth, validação de `schemaVersion`, store idempotente (disco
primeiro, Postgres depois), `/healthz`, resposta `202`.

- **Entregue:** módulo Go [`analysis-service/`](analysis-service/) (stdlib-only):
  ingest com auth bearer + validação + persistência idempotente lossless em disco,
  `/healthz`. Testes carregam as fixtures reais do `schema/` (202, idempotência,
  401, 400). Consumidor fechado contra o contrato do M0.

### B2 — Tools como leitores finos ✅

Catálogo MCP lendo **fatias do JSON** guardado. Sem JFR — é quase mapeamento de
campo. Espelha os nomes do `McpToolRegistry` atual.

- **Entregue:** pacote `internal/tools` (`Registry` + renders portando o texto das
  tools Java: triage, exception, gc, alloc, lock, thread, baseline). Exposto via
  HTTP (`GET /v1/incidents/{id}/tools/{tool}`, `GET /v1/incidents/{id}`,
  `GET /v1/tools`). Testes alimentam as fixtures e conferem cada tool. `go vet`/
  build/test verdes.

### B3 — Loop do agente ✅

Provider **BYO-LLM** + function calling + saída `Laudo` (confiança + evidência).
Mais arriscado e sensível a token, mas **não bloqueia o ingest**. Conferir a
referência da Claude API para o cliente LLM — não chutar model id.

- **Entregue:** pacote `internal/agent` (porte do `AgentLoop`/`StubLlmProvider`):
  `Provider` (fronteira de IA, BYOK), `Stub` determinístico, `Loop` (function
  calling sobre o `Registry`, teto de tool calls, `parseLaudo`/`extractJSON`),
  `Laudo`. Endpoint `POST /v1/incidents/{id}/investigate`. Testes: convergência
  com stub, teto de turnos, erro de provider, parse. `go vet`/build/test verdes.
- **Falta na fatia do provider real:** implementar `Provider` para Gemini/Claude
  (BYOK via env) — aí sim consultar a referência da Claude API. O `Stub` mantém o
  circuito fechado e testável sem key até lá.

### B4 — Sinks

Dispatch pros canais: log → e-mail → Slack → WhatsApp.

---

## M-int — Integração ponta a ponta

Java emite JSON real → Go ingere → agente → sink. Primeiro circuito fechado no split.

---

## Fase 5.5 — endurecimento (NÃO é um bloco em paralelo; decompõe)

Cada item tem um gatilho diferente:

| Item 5.5 | Quando | Por quê |
|---|---|---|
| **#1 Dump assíncrono + single-flight** | **Junto com A1** (não depois) | A1 já adiciona trabalho ao caminho pós-snapshot, e isso **não pode** rodar na thread do request. O executor single-thread em background + semáforo de 1 é pré-requisito do A1, não add-on. |
| **#3 JFR config tunado (`.jfc`)** | Tarefa curta avulsa, **antes do teste de carga** | Baixo risco, alto retorno em overhead. `profile` de prateleira é agressivo demais. |
| **#2 Dedup/baseline em Redis** | **Por último, só antes de ir pra frota** | Só importa em multi-pod (senão a mesma incidência dispara uma vez por pod). Pra demo/single-instance não é necessário — não pague a complexidade cedo. |

---

## Ordem final, achatada

1. **M0** — congelar schema + fixtures
2. **A1 + 5.5#1** — extração eager pro record + dump async/single-flight *(vale sozinho)*
3. **B1** — ingest + store no Go *(em paralelo com A1, contra fixtures)*
4. **A2** — durabilidade + POST
5. **B2 → B3 → B4** — tools, agente, sinks
6. **5.5#3** — JFR config tunado (antes de medir carga)
7. **M-int** — integração ponta a ponta
8. **5.5#2** — Redis, só quando for pra frota

O passo 2 é o primeiro que mexe em código e o de maior alavancagem — começa a pagar
antes do split terminar.
