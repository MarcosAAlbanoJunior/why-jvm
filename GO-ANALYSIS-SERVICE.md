# Serviço de Análise (Go) — contrato do split

O que o serviço de análise escrito em **Go** recebe do lado Java e o que ele
precisa construir para investigar o incidente e despachar o laudo pros canais.

É o desenho da **Fase 5** (split de produção) do roadmap. Enquanto não existir,
tudo roda in-process em Java (modo simples). Este doc é o handoff para a extração.

> TL;DR: o **Java extrai** os agregados do JFR num JSON durável e os **envia por
> HTTP**. O **Go recebe esse JSON**, serve as tools MCP como leitores finos sobre
> ele (sem nunca parsear JFR), roda o loop do agente (BYO-LLM) e posta o laudo nos
> canais. O Go é um binário leve que **sobrevive ao OOM do app**.

---

## 0. A decisão de fronteira: por que Java extrai e Go orquestra

Ler JFR é uma **API nativa do Java** (`jdk.jfr.consumer.RecordingFile`). Não há
parser de JFR maduro em Go — o do Pyroscope/Grafana é parcial e focado em
profiling. Reimplementar o parsing de GC, lock, alocação e exception em Go seria
trabalho recorrente e frágil, correndo atrás de cada tipo de evento.

Então a fronteira **não** é "MCP inteiro em Go". É:

| Capacidade | Onde mora | Por quê |
|---|---|---|
| Gatilho + captura (`takeSnapshot`) | **Java**, no `-javaagent` | Só a JVM tira foto do próprio buffer JFR |
| **Extração** dos agregados do JFR | **Java**, em background pós-snapshot | Parsing de JFR é API nativa do Java |
| Transporte do incidente (JSON) | Java → Go, HTTP | Payload compacto; o `.jfr` cru não cruza a rede |
| Tools MCP (servir agregados ao agente) | **Go**, leitores finos sobre o JSON | Não precisa de JFR — só fatiar JSON já pronto |
| Loop do agente (function calling, BYO-LLM) | **Go** | Orquestração, agnóstica de linguagem |
| Sinks / dispatch pros canais | **Go** | IO leve |

A fronteira de linguagem casa com a de capacidade: **JFR→Java, orquestração/IO→Go.**

O **portão 2** (agregação progressiva) continua intacto: o Java computa **todas** as
dimensões de uma vez (determinístico, custo zero de token), e o Go só entrega ao
LLM a dimensão que o agente pedir. Bônus: como o agregado já é JSON, o `.jfr` pode
ser arquivado/descartado e a **re-investigação** (rodar o agente de novo, com
modelo ou prompt melhor, sobre a evidência congelada) funciona sobre o JSON.

---

## 1. Topologia

```
   app JVM + -javaagent (Java, LEVE)
   ┌──────────────────────────────────────────────┐
   │ SpanProcessor.onEnd → gatilho (erro? lento?)  │
   │   dispara → takeSnapshot()  [freeze, síncrono]│
   │   background: parse JFR → AGREGADOS            │
   │              → IncidentRecord (JSON)           │
   │   persiste local (fallback) + POST /incidents  │
   └───────────────────────┬──────────────────────┘
                            │ HTTP (JSON), idempotente por incidentId
                            ▼
   why-jvm analysis service (Go, sempre de pé, sobrevive ao OOM do app)
   ┌──────────────────────────────────────────────┐
   │ ingest  → store (Postgres/disk), idempotente  │
   │ tools MCP = leitores finos sobre o JSON       │
   │ loop do agente (BYO-LLM, function calling)    │
   │ laudo estruturado → sinks                     │
   └───────────────────────┬──────────────────────┘
                            ▼
              Slack / WhatsApp (Evolution) / e-mail / dashboard
```

Dois modos sobre a mesma camada de tools (de graça, como hoje em Java):

- **Autônomo** — o ingest dispara o loop do agente; laudo vai pro sink.
- **Interativo** — um host MCP (Claude Desktop) conecta e pergunta "o que houve com
  o /checkout às 14h?"; o LLM investiga sob demanda com as mesmas tools.

---

## 2. O contrato: o `IncidentRecord` JSON que o Java envia

Hoje o `IncidentRecord` (em `core`) carrega os escalares + o **caminho** do `.jfr`.
No split, o Java troca o caminho pelos **agregados já extraídos**. Este é o schema
de fronteira — a única coisa que Java e Go compartilham. Versionar (`schemaVersion`).

> **Fonte canônica:** [`schema/incident-record.v1.json`](schema/incident-record.v1.json)
> (JSON Schema draft 2020-12) + fixtures validadas em [`schema/`](schema/). O JSON
> abaixo é ilustrativo; o arquivo é normativo.

```jsonc
{
  "schemaVersion": 1,
  "incidentId": "2026-06-15T14:03:07Z-checkout-a1b2c3",
  "capturedAt": "2026-06-15T14:03:07.812Z",
  "endpoint": "POST /checkout",
  "type": "ERROR",                 // ERROR | SLOW
  "fingerprint": "POST /checkout|NullPointerException@InvoiceBuilder.buildLineItems",
  "threadName": "http-nio-8080-exec-7",
  "durationMs": 4200,
  "occurrenceCount": 1,            // dedup: nº de vezes colapsadas neste fingerprint/cooldown

  // presente quando type == ERROR
  "exception": {
    "type": "java.lang.NullPointerException",
    "message": "Cannot invoke ...",
    "stackTrace": "java.lang.NullPointerException\n\tat io.app.InvoiceBuilder.buildLineItems(...)\n\t..."
  },

  // baseline do endpoint, para a triagem comparar
  "baseline": { "p99Ms": 380, "sampleCount": 5000, "thresholdMultiplier": 3.0 },

  // sinais headline da triagem (JfrCorrelation.Signals) — uma passada barata
  "triageSignals": {
    "gcCount": 3, "longestGcPauseMs": 812, "totalGcPauseMs": 1340,
    "totalLockWaitMs": 0, "totalAllocBytes": 1288490188
  },

  // AGREGADOS por dimensão — já no formato que cada tool devolve hoje em Java.
  // Cada um pode vir null se o JFR não tinha aquela dimensão na janela.
  "dimensions": {
    "slowTraces": [
      { "span": "InvoiceBuilder.buildLineItems", "selfMs": 3100, "totalMs": 4000 }
    ],
    "gcActivity": {
      "pauses": [ { "at": "14:03:07.000Z", "kind": "G1 Full", "pauseMs": 812,
                    "heapBeforeMb": 3800, "heapAfterMb": 3600 } ],
      "count": 3, "totalPauseMs": 1340
    },
    "allocationHotspots": [
      { "site": "io.app.InvoiceBuilder.buildLineItems", "bytes": 1288490188, "pct": 73.0 }
    ],
    "lockContention": [
      // { "monitor": "...", "thread": "...", "waitMs": ..., "stack": "..." }
    ],
    "threadActivity": {
      // eventos JFR atribuídos à threadName na janela (espera vs ruído de fundo)
      "onCpuPct": 18.0, "waitingPct": 76.0, "topStacks": [ "..." ]
    }
  },

  // metadados da JVM no momento (contexto)
  "jvmContext": { "heapUsedMb": 3600, "heapMaxMb": 4096, "gcName": "G1", "threadCount": 214 },

  // opcional: ponteiro pro .jfr arquivado, se o Java guardou (re-investigação profunda)
  "jfrArtifactUri": "s3://why-jvm/incidents/2026-06-15/.../snapshot.jfr"
}
```

O Go **não** olha `jfrArtifactUri` para servir tools — só os campos já agregados.
O artefato existe só para auditoria e re-extração futura (se um dia quiser uma
dimensão nova, re-extrai no Java, não parseia no Go).

O **laudo** que o Go produz mantém o formato do `Laudo` atual de `core`:
`{ endpoint, tipo, causaRaiz, evidencia[], confianca, correcaoSugerida }`.

---

## 3. Transporte e durabilidade

- **Endpoint:** `POST /v1/incidents`, body = o JSON da seção 2. Auth por token
  (`Authorization: Bearer …`) ou mTLS. Responde `202 Accepted` rápido (não bloqueia
  o app esperando a investigação — o loop do agente roda async no Go).
- **Idempotência:** chave = `incidentId`. Reenvio do mesmo id é no-op (o app pode
  reenviar em retry). O Go dedupa no store.
- **Durabilidade no lado Java:** o Java **persiste o `IncidentRecord` localmente
  antes** de tentar o POST e só marca como enviado no ack. Se o Go estiver fora,
  re-tenta com backoff a partir do disco. Assim nenhum incidente se perde se o
  serviço de análise estiver caído — que é justo quando o app está em apuros.
- **Backpressure:** o Go tem fila bounded de investigações; se encher, enfileira no
  store e processa em ritmo, nunca derruba o ingest.

---

## 4. O que o serviço Go precisa construir

### 4.1 Ingest
- HTTP server (`net/http` basta), rota `POST /v1/incidents`, auth, validação do
  schema (`schemaVersion`), resposta `202`. Health/readiness em `/healthz`.

### 4.2 Store
- Persistência idempotente por `incidentId`. Postgres (`jsonb` + colunas indexadas:
  `endpoint`, `fingerprint`, `type`, `captured_at`) ou disco para começar. Guarda o
  record cru + o laudo produzido. É o que habilita re-investigação e pós-morte.

### 4.3 Camada MCP (tools = leitores finos sobre o JSON)
- Cada tool do catálogo lê o `IncidentRecord` do store e devolve **só** a fatia
  pedida — **nada de parsear JFR**. Catálogo espelha o de Java:
  `triage`, `get_slow_traces`, `get_exception_details`, `get_gc_activity`,
  `get_allocation_hotspots`, `get_lock_contention`, `get_thread_activity`,
  `get_endpoint_baseline`.
- `triage` lê `triageSignals` + `baseline` e devolve a hipótese inicial + flags
  (GC alto? lock? downstream?). Continua sendo a 1ª chamada e o coração do portão 2.
- Exponha as tools via **MCP server** (SDK Go ou implementação stdio/HTTP) para o
  **modo interativo**; o **modo autônomo** chama as mesmas funções in-process.

### 4.4 Loop do agente
- Abstração de provider **BYO-LLM** (a key vem do ambiente do serviço Go, nunca é
  guardada): Gemini / Claude / OpenAI / Ollama. Espelha o `LlmProviders.fromEnv()`
  do módulo `llm/`. **Para construir o cliente LLM, confira a referência da Claude
  API** (modelos, function calling, streaming) — não chute model id de memória.
- Loop de function calling: `system` (papel + "comece por triage, drill só na
  dimensão suspeita, não chame dimensão irrelevante") + incidente (já com a hipótese
  da triagem) → modelo chama tools → converge no laudo.
- **Saída estruturada** no formato `Laudo`. Anti-alucinação: a correlação numérica
  fica na triagem determinística (vinda do Java), o agente **narra e prioriza, não
  calcula**; exija sempre `confianca` + `evidencia[]` para o laudo ser auditável.

### 4.5 Sinks / dispatch
- Interface `Sink` (espelha `core`): `LoggingSink`, e-mail (SMTP), Slack (webhook),
  WhatsApp (Evolution API). Selecionável por env. Posta o laudo formatado por canal.

### 4.6 Config
- Tudo por env (BYOK): `WHYJVM_LLM_PROVIDER`, `WHYJVM_LLM_API_KEY`, `WHYJVM_LLM_MODEL`,
  `WHYJVM_SINK`, credenciais de canal, `WHYJVM_STORE_DSN`, `WHYJVM_INGEST_TOKEN`.

---

## 5. Não-objetivos (importante)

- **O Go NÃO parseia JFR.** Se faltar uma dimensão, a extração nova é no Java; o Go
  consome JSON pronto. Isso é o que mantém o binário Go leve e sem dependência de JVM.
- **O Go NÃO guarda credencial de LLM além do processo** (BYOK, lê do ambiente).
- **O Go NÃO remedia sozinho.** v1 é advisor: posta laudo, humano decide. A sugestão
  de fix é texto/PR proposto, nunca merge automático.
- **A RCA é intra-JVM.** Responde "por que esta JVM", não "qual dos N serviços".
  Correlação distribuída é outra camada (traces), fora do escopo deste serviço.

---

## 6. Checklist de implementação

- [ ] Lado Java: trocar o `IncidentRecord` (caminho do `.jfr`) por extração eager
      dos agregados → JSON da seção 2; persistir local + `POST /v1/incidents` com
      retry/backoff a partir do disco.
- [ ] Go: HTTP ingest (`POST /v1/incidents`), auth, validação de `schemaVersion`,
      resposta `202`, `/healthz`.
- [ ] Go: store idempotente por `incidentId` (Postgres `jsonb` ou disco).
- [ ] Go: camada de tools como leitores finos sobre o JSON (catálogo da seção 4.3),
      sem parsing de JFR.
- [ ] Go: MCP server (stdio/HTTP) para o modo interativo.
- [ ] Go: loop do agente com provider BYO-LLM e saída `Laudo` (confiança + evidência).
- [ ] Go: sinks selecionáveis por env (log, e-mail, Slack, WhatsApp).
- [ ] Go: fila bounded + backpressure no ingest.
- [ ] Versionar o schema JSON e manter Java e Go em sincronia por `schemaVersion`.
