# otel-extension — why-jvm zero-código

Extensão do **agente OpenTelemetry** que pluga o why-jvm em **qualquer app Java sem
tocar no código**. O agente OTel já auto-instrumenta HTTP/JDBC/etc.; esta extensão
registra, via SPI, o nosso `SpanProcessor` no tracer provider do agente — daí o
gatilho de erro/lentidão e a captura JFR funcionam de fora.

> Bônus: como o agente OTel instrumenta JDBC, as queries viram sub-spans
> automaticamente — o **N+1 (Tier 3)** funciona em apps reais sem spans manuais.

## Build

```bash
./gradlew :otel-extension:shadowJar
# -> otel-extension/build/libs/why-jvm-otel-extension-<versão>-all.jar
```

O jar é shaded: **não** contém a SDK do OTel (o agente provê em runtime, classloader
isolado) e leva o Jackson relocado, sem conflitar com o app host.

## Rodar (modo split — recomendado)

Com o `analysis-service` (Go) rodando à parte e recebendo os incidentes:

```bash
java -javaagent:opentelemetry-javaagent.jar \
     -Dotel.javaagent.extensions=/caminho/why-jvm-otel-extension-<versão>-all.jar \
     -Dwhyjvm.forward.url=http://localhost:9090 \
     -Dwhyjvm.app.packages=com.suaapp \
     -Dwhyjvm.source.dirs=/opt/suaapp/src/main/java \
     -jar suaapp.jar
```

Sem `whyjvm.forward.url`, a extensão cai no default leve do `core` (`LoggingSink` +
`StubLlmProvider`): registra o laudo no log, sem LLM nem rede. Útil pra um teste rápido.

## Config

Cada chave aceita system property (`-Dwhyjvm.x.y`) **ou** env var equivalente
(`WHYJVM_X_Y`) — é lida do `ConfigProperties` do agente OTel.

| Chave | Default | O que é |
|---|---|---|
| `whyjvm.forward.url` | — | URL do `analysis-service` (Go). Setada → modo **split**. |
| `whyjvm.forward.token` | — | Bearer opcional do ingest. |
| `whyjvm.incident.dir` | `incidents` | Diretório dos incidentes/outbox. |
| `whyjvm.app.packages` | — | Pacotes-raiz da app (lista por vírgula), p/ o code-aware (Tier 2). |
| `whyjvm.source.dirs` | — | Diretórios de fonte da app (lista por vírgula), p/ o code-aware. |
| `whyjvm.cooldown` | `10m` | Janela de dedup por fingerprint. |
| `whyjvm.slow.threshold` | `3.0` | Múltiplo do p99 móvel que marca SLOW. |

## Notas

- **Processor síncrono** (não `BatchSpanProcessor`): o gatilho roda no `onEnd` na
  thread do request, e é dali que a captura atribui a atividade JFR àquela thread.
- **Não rode o LLM no app** (modo simples): pesado e morre junto no OOM. O modo split
  mantém só captura + forward no app; a investigação roda no serviço Go.
- JFR usa a API `jdk.jfr` programática — sem flag extra em qualquer JDK 21.
