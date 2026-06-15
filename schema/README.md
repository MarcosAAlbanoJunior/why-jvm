# schema/ — o contrato de fronteira do split

A **única** superfície compartilhada entre o lado Java (produz) e o serviço de
análise em Go (consome). É o que a Fase 5 manda pela rede no lugar do `.jfr`.

| Arquivo | O que é |
|---|---|
| [`incident-record.v1.json`](incident-record.v1.json) | JSON Schema (draft 2020-12) do `IncidentRecord`. **Fonte canônica** do contrato. |
| [`fixtures/incident-error.json`](fixtures/incident-error.json) | Exemplo `ERROR` (NullPointerException). |
| [`fixtures/incident-slow-gc.json`](fixtures/incident-slow-gc.json) | Exemplo `SLOW` dominado por pausa de GC + alocação. |
| [`fixtures/incident-slow-lock.json`](fixtures/incident-slow-lock.json) | Exemplo `SLOW` dominado por contenção de lock. |

Contexto e racional: [GO-ANALYSIS-SERVICE.md](../GO-ANALYSIS-SERVICE.md) (seção 2) e a
ordem de trabalho em [ROADMAP.md](../ROADMAP.md) (M0).

## Regra de versionamento

- O campo `schemaVersion` é `const: 1`. Java e Go validam por ele.
- **Mudança compatível** (campo novo opcional/nullable): mantém `v1`.
- **Mudança quebrada** (renomear/remover campo, apertar required): cria
  `incident-record.v2.json`, bumpa `schemaVersion`, e os dois lados passam a
  negociar pela versão. Nunca edite a forma de uma versão já publicada.
- Os tipos do `core` (`io.whyjvm.capture`) espelham o schema 1:1: `TriageSignals`,
  `GcActivity` (+`Pause`), `AllocationHotspots` (+`Site`), `LockContention` (+`Site`),
  `ThreadActivity`, `ExceptionInfo`, `Baseline`, `JvmContext`, `Dimensions`. O
  `EvidenceExtractor` os popula numa passada no JFR (A1, concluído).

### Campos ainda não preenchidos pela captura atual

O schema é o **alvo** do `IncidentRecord` pós-split. Estes campos já estão no
contrato e no record, mas a captura ainda os deixa `null`/default:

- `dimensions.slowTraces` — exige a captura da árvore do trace (Fase 3, pendente).
- `jvmContext` — métricas da JVM no momento (heap/threads), ainda não coletadas.
- `occurrenceCount` — default `1`; ligar ao dedup (`IncidentDeduplicator`) é trabalho à parte.
- `baseline` — vem do `LatencyBaseline`; ainda não anexado ao record na captura.

## Como revalidar

As fixtures **devem** validar contra o schema. Com Node disponível:

```bash
npm install ajv@8 ajv-formats@3
node -e "import('ajv/dist/2020.js').then(async ({default:Ajv})=>{const af=(await import('ajv-formats')).default;const fs=await import('node:fs');const s=JSON.parse(fs.readFileSync('schema/incident-record.v1.json','utf8'));const ajv=new Ajv({allErrors:true,strict:false});af(ajv);const v=ajv.compile(s);for(const f of ['error','slow-gc','slow-lock']){const d=JSON.parse(fs.readFileSync('schema/fixtures/incident-'+f+'.json','utf8'));console.log((v(d)?'PASS ':'FAIL ')+f);if(!v(d))console.log(JSON.stringify(v.errors,null,2));}})"
```

O loop produtor↔contrato também é validado em Java (CI): o `IncidentRecordSchemaTest`
(módulo `core`) serializa um `IncidentRecord` com o mapper de produção
(`IncidentRecordJson`) e valida o JSON contra este schema com o networknt. Se a
forma do record divergir do schema, o teste quebra.
