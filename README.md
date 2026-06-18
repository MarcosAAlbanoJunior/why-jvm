# why-jvm-mcp

Root Cause Analysis automático para aplicações Java, acionado por gatilho. Quando
um endpoint dá **erro** ou fica **lento**, um gatilho determinístico **congela**
uma janela de evidências (JFR + OpenTelemetry) e um agente investiga e explica, em
linguagem natural, o que aconteceu e por quê.

A premissa: a IA nunca lê o firehose. Ela só roda quando algo quebra, e mesmo assim
só enxerga agregados do incidente — é o que torna o custo de token viável.

## Como funciona

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

Dois portões controlam o custo: o **gatilho** decide *quando* a IA roda (sem
incidente, custo zero); a **agregação** decide *quanto* ela lê (as tools devolvem
agregados, nunca eventos crus).

## O que detecta

- **Erro** — span com status ERROR dispara na hora.
- **Lentidão** — baseline móvel de p99 por endpoint, sem limiar global.
- **Controle de tempestade** — fingerprint + dedup + cooldown: uma investigação
  por fingerprint a cada janela; as demais ocorrências só incrementam um contador.
- **Code-aware (Tier 2)** — anexa ao laudo o trecho de fonte do método suspeito.
- **Árvore do trace (Tier 3)** — expõe padrões como N+1 de JDBC nos sub-spans.
- **Dimensões JFR** — GC, alocação, contenção de lock, atividade de thread.

## Módulos

| Módulo | O que é |
|---|---|
| [`core/`](core/) | A biblioteca — o produto. Gatilho, captura, tools MCP, agente, sink. JAR puro, sem Spring. |
| [`otel-extension/`](otel-extension/) | Extensão do agente OpenTelemetry: pluga o why-jvm em qualquer app Java **zero-código** (`-javaagent` + `-Dotel.javaagent.extensions=...`). Caminho recomendado. |
| [`sinks/`](sinks/) | Sinks reais que puxam dependência de transporte (e-mail via Jakarta Mail; Slack/WhatsApp depois). Fora do `core` para mantê-lo puro. |
| [`llm/`](llm/) | Provider de IA real (`LangChain4jProvider`) — Gemini via LangChain4j, multi-provider por env. Também fora do `core`. |
| [`analysis-service/`](analysis-service/) | O lado **Go** do split: recebe o `IncidentRecord` JSON do Java, persiste e roda a investigação fora do app. |

## Usar (zero-código, recomendado)

O why-jvm é uma **extensão do agente OpenTelemetry** — pluga sem tocar no código da
aplicação. O agente OTel já auto-instrumenta HTTP/JDBC; a extensão registra o
gatilho + a captura por cima.

```bash
java -javaagent:opentelemetry-javaagent.jar \
     -Dotel.javaagent.extensions=why-jvm-otel-extension-all.jar \
     -Dwhyjvm.forward.url=http://localhost:9090 \
     -Dwhyjvm.app.packages=com.suaapp \
     -jar suaapp.jar
```

Build do jar, modos e todas as chaves de config: [otel-extension/README.md](otel-extension/README.md).

### Modos

- **Split** (com `whyjvm.forward.url`) — o Java só captura e encaminha o
  `IncidentRecord` ao [`analysis-service`](analysis-service/) (Go), que roda o
  agente e despacha. Leve, sobrevive ao OOM do app, sem key de LLM dentro do app.
- **Simples** (sem `forward.url`) — o agente roda in-process e o sink publica o
  laudo. Default: `LoggingSink` + `StubLlmProvider` (degrada honesto, sem rede).

## Usar (como biblioteca)

Para apps que já montam o próprio `SdkTracerProvider`:

```java
WhyJvm whyjvm = WhyJvm.builder()
        .llmProvider(...)   // BYOK; default StubLlmProvider
        .sink(...)          // default LoggingSink
        .build();
tracerProvider.addSpanProcessor(whyjvm.spanProcessor());
```

### Code-aware RCA (Tier 2)

Em erros, o laudo pode incluir o **trecho de fonte** do método no topo do stack da
app — não só "NPE na linha 20", mas o código com a linha marcada. Configurado por
env, app-agnóstico (o agente in-JVM resolve o fonte; o serviço Go nunca o acessa):

| Chave | Env | O que é |
|---|---|---|
| `whyjvm.app.packages` | `WHYJVM_APP_PACKAGES` | pacotes-raiz da app (ex.: `com.acme`), para separar o frame da app do de framework/JDK. |
| `whyjvm.source.dirs` | `WHYJVM_SOURCE_DIRS` | diretórios de fonte da app. |

Sem elas, degrada honesto para *"fonte indisponível em runtime"* (nomeia o método,
sem o trecho).

## Requisitos

- JDK 21 (LTS). O `jdk.ObjectAllocationSample` (hotspots de alocação) precisa de JDK 16+.
- Gradle vem pelo wrapper (`./gradlew`), não precisa instalar.

## Design

Aprofundamento em [`docs/`](docs/): visão geral do design
([INSTRUCTION](docs/INSTRUCTION.md)), guia de integração
([INTEGRATION](docs/INTEGRATION.md)), contrato do split
([GO-ANALYSIS-SERVICE](docs/GO-ANALYSIS-SERVICE.md)), profundidade do RCA
([RCA-DEPTH](docs/RCA-DEPTH.md)) e roadmap ([ROADMAP](docs/ROADMAP.md)).
