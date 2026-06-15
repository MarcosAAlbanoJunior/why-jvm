# Integração em um app Java real

Como plugar o why-jvm num app de verdade para receber alertas de **erro** e
**lentidão**. Resume o estado atual (integração como biblioteca) e o plano para
o alvo desejado: **zero-código via `-javaagent`**.

> TL;DR: hoje funciona como **biblioteca** (adicionar deps + registrar o
> `SpanProcessor` + ter spans OTel). O **zero-código** exige construir um módulo
> de **extensão do agente OpenTelemetry** — ainda não feito. Plano abaixo.

---

## 1. Modelos de integração

| Cenário | Estado |
|---|---|
| Lib + registrar o processor + spans OTel manuais (como o `sample-app`) | ✅ funciona |
| Lib num Spring Boot real com starter OTel | ✅ funciona, com wiring |
| `-javaagent` zero-código (extensão do agente OTel) | ❌ falta o módulo de extensão |
| LLM in-process em produção | ⚠️ funciona, mas pesado e morre junto no OOM — preferir o split (Fase 5) |

---

## 2. Hoje: integração como biblioteca

Dois requisitos no app alvo:

1. **Existir spans OTel.** O gatilho lê `span.getStatus() == ERROR` e a duração do
   span. Sem spans, nada dispara. Os spans podem vir de instrumentação manual
   (como o `TracingFilter` do `sample-app`) ou automática (starter OTel do Spring
   Boot, agente OTel, etc.).
2. **Registrar o nosso `SpanProcessor` DIRETO** no `SdkTracerProvider` — **não**
   atrás de um `BatchSpanProcessor`.

```java
WhyJvm whyJvm = WhyJvm.builder()
        .incidentDir(Path.of("incidents"))
        .llmProvider(LlmProviders.create(provider, apiKey, model)) // ou deixe no StubLlmProvider
        .sink(new EmailSink(smtpConfig))                            // ou LoggingSink
        .cooldown(Duration.ofMinutes(10))
        .slowThreshold(3.0)
        .build();

SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
        .addSpanProcessor(whyJvm.spanProcessor())  // DIRETO, não BatchSpanProcessor
        .build();
```

### ⚠️ Por que NÃO pode ser `BatchSpanProcessor`
O `get_thread_activity` atribui os eventos JFR à **thread que atendeu o request**,
e captura o nome dela em `onEnd` — que roda na própria thread do request quando o
processor é síncrono. Um `BatchSpanProcessor` chama `onEnd` numa thread de batch,
quebrando essa atribuição (e a lentidão por espera viraria ruído de novo).

### JFR funciona em qualquer JVM
A captura usa a API `jdk.jfr` programática (`Recording` + `dump`), então roda em
qualquer JDK 21 sem flag extra. `jdk.ObjectAllocationSample` precisa de JDK 16+.

---

## 3. Objetivo: zero-código via `-javaagent` (a fazer)

Escrever um `-javaagent` do zero (com `premain` + instrumentar HTTP/JDBC via
bytecode) é reinventar o agente OTel — **não fazer isso**. O caminho certo é
pegar carona no **agente OpenTelemetry**, que já auto-instrumenta HTTP/JDBC/etc.
sem tocar no app, e empacotar o why-jvm como uma **extensão** dele:

```bash
java -javaagent:opentelemetry-javaagent.jar \
     -Dotel.javaagent.extensions=why-jvm-otel-extension.jar \
     -Dwhyjvm.llm.api-key=... -Dwhyjvm.mail.to=... \
     -jar seuapp.jar
```

### O que construir (`why-jvm-otel-extension`)
1. **Módulo novo** `otel-extension/` dependendo de `core` (e opcionalmente
   `sinks`/`llm` — ver nota de produção abaixo).
2. **SPI de autoconfig**: classe que implementa
   `io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider`
   e adiciona o nosso processor ao tracer provider do agente:
   ```java
   public final class WhyJvmAutoConfig implements AutoConfigurationCustomizerProvider {
       @Override
       public void customize(AutoConfigurationCustomizer c) {
           c.addTracerProviderCustomizer((builder, props) -> {
               WhyJvm whyJvm = WhyJvm.builder()
                       .sink(sinkFromConfig(props))
                       .llmProvider(providerFromConfig(props))
                       .build();
               return builder.addSpanProcessor(whyJvm.spanProcessor());
           });
       }
   }
   ```
3. **Registro do SPI**: arquivo
   `META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider`
   com o nome da classe.
4. **Config** via system properties / env (`whyjvm.llm.api-key`, `whyjvm.mail.*`,
   etc.) lidas de `ConfigProperties`, no mesmo espírito BYOK do `sample-app`.
5. **Empacotar shaded** (Shadow plugin). Cuidado com o **classloader isolado** do
   agente OTel: NÃO empacote a API/SDK do OTel (o agente provê); empacote/relocate
   só as nossas deps próprias (Jackson, e — se for o modo simples — Jakarta Mail /
   LangChain4j).

Referência: "Extending the OpenTelemetry Java agent" (docs oficiais) — exemplo de
extensão com `AutoConfigurationCustomizerProvider`.

---

## 4. Nota de produção: não rode o LLM dentro do app

Colocar o LangChain4j na extensão joga o LLM **dentro do processo de produção**:
pesado, propenso a conflito de dependência com o host e — pior — **morre junto no
OOM**, justo o incidente que você quer diagnosticar.

Dois modos:

- **Modo simples (dev/portfólio):** a extensão bundla tudo (captura + agente +
  sink). Um processo só. Aceita o peso. Bom para validar.
- **Modo produção (Fase 5):** a extensão bundla só o **leve** — gatilho + captura
  JFR + gravação do incidente durável. A **investigação (LLM)** roda **fora**, num
  serviço why-jvm separado que lê os incidentes (transporte HTTP/MCP, store em
  Postgres). Assim a IA sobrevive ao app cair, e produção não carrega LangChain4j.

---

## 5. Checklist para a próxima vez

- [ ] Criar módulo `otel-extension/` (depende de `core`).
- [ ] Implementar `WhyJvmAutoConfig` (`AutoConfigurationCustomizerProvider`) +
      registro em `META-INF/services`.
- [ ] Ler config (`whyjvm.*`) de `ConfigProperties` (BYOK).
- [ ] Empacotar shaded com Shadow, sem a SDK do OTel, cuidando do classloader.
- [ ] README do módulo com o comando `-javaagent ... -Dotel.javaagent.extensions=...`.
- [ ] Decidir modo simples (tudo junto) vs produção (extensão = só captura,
      investigação fora — Fase 5).
- [ ] Garantir que o processor entra **síncrono** (não BatchSpanProcessor), senão
      o `get_thread_activity` perde a thread do request.
