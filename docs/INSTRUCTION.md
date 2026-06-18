# Observabilidade MCP para JVM: RCA acionado por gatilho

Root Cause Analysis automático para aplicações Java. Quando um endpoint dá erro ou fica lento, um gatilho determinístico congela uma janela de evidências (JFR + OpenTelemetry) e dispara um agente de IA que investiga e explica, em linguagem natural, o que aconteceu e por quê.

A premissa central: a IA nunca lê o firehose. Ela só é acionada quando algo quebra, e mesmo assim só enxerga uma janela agregada do incidente. Isso é o que torna o custo de token viável.

---

## 1. O que o sistema faz, em uma frase

Um span termina com status de erro ou com latência acima do baseline, o gatilho dispara, o sistema captura um pacote de evidências daquele momento, o agente investiga esse pacote chamando ferramentas de diagnóstico e devolve um laudo: causa, evidência, confiança e sugestão de correção.

---

## 2. Os dois portões de custo

Antes da arquitetura, o conceito que rege tudo. Existem dois portões que controlam quanto token você gasta.

**Portão 1, o gatilho.** Decide *quando* a IA roda. Sem incidente, a IA não é invocada. Custo zero em regime normal.

**Portão 2, a agregação progressiva.** Decide *quanto* a IA lê por investigação. As ferramentas nunca devolvem eventos crus. Devolvem agregados (top 5 call sites por bytes alocados, não 200 mil eventos de alocação). O agente começa por uma ferramenta de triagem barata e só faz drill-down na dimensão que parecer suspeita. Se o problema é GC, ele nunca pede dados de lock.

A maioria dos projetos parecidos erra por ignorar o portão 2. Eles disparam a IA certo, mas jogam um dump gigante no contexto e o custo explode mesmo assim.

---

## 3. Arquitetura e fluxo

```
                  app Java (Spring Boot)
   +------------------------------------------------+
   |  OTel SpanProcessor.onEnd   JFR (rolling buffer)|
   +----------------+-------------------+------------+
                    |                   |
              (avalia cada span)   (eventos contínuos)
                    |                   |
                    v                   |
            +---------------+           |
            |    GATILHO    |  erro? lento? dedup? cooldown?
            +-------+-------+           |
                    | dispara          |
                    v                   v
            +------------------------------------+
            |   CAPTURA DE EVIDÊNCIA (snapshot)  |
            |   congela janela [T-delta, T]      |
            |   traces + JFR.takeSnapshot()      |
            +-----------------+------------------+
                              | grava incidente durável
                              v
            +------------------------------------+
            |   SERVIDOR MCP (ferramentas)       |
            +-----------------+------------------+
                              | tools
                              v
            +------------------------------------+
            |   AGENTE (loop de function calling) |
            |   investiga -> hipótese -> drill    |
            +-----------------+------------------+
                              | laudo estruturado
                              v
                  Sink: Slack / WhatsApp / dashboard
```

Cinco componentes: gatilho, captura, servidor MCP, agente, sink. Os três primeiros são determinísticos e baratos. O agente é o único que gasta token, e só roda por incidente.

---

## 4. O gatilho (detecção)

O ponto de instrumentação mais limpo no mundo Java é um `SpanProcessor` do OpenTelemetry. Todo span que termina passa por `onEnd`, e ali você tem status, duração e exceptions registradas no span. É barato porque é só uma checagem por request, em memória.

```java
public class IncidentTriggerProcessor implements SpanProcessor {

    @Override
    public void onEnd(ReadableSpan span) {
        SpanData s = span.toSpanData();
        boolean isError = s.getStatus().getStatusCode() == StatusCode.ERROR;
        long durationMs = (s.getEndEpochNanos() - s.getStartEpochNanos()) / 1_000_000;
        boolean isSlow = baseline.isAnomalous(s.getName(), durationMs);

        if ((isError || isSlow) && dedup.shouldFire(fingerprint(s))) {
            triggerService.fire(new Incident(s, isError ? ERROR : SLOW));
        }
    }

    @Override public boolean isEndRequired() { return true; }
    @Override public boolean isStartRequired() { return false; }
}
```

### Detecção de lentidão sem falso positivo

Limiar fixo não serve, porque um endpoint que é naturalmente lento alertaria o tempo todo. Use um baseline móvel por endpoint. Para começar, algo simples já resolve: mantenha um p99 móvel por nome de span e dispare quando uma requisição passar de, digamos, 3x esse p99. Depois você pode trocar por EWMA ou desvio padrão. Não comece complexo.

### Controle de tempestade (o ponto que protege seu bolso)

Quando algo quebra, dez mil requisições falham. Disparar dez mil investigações é um apocalipse de token. O gatilho precisa de:

- **Fingerprint do incidente**: agrupe por (endpoint, assinatura do erro). A assinatura do erro é tipicamente a classe da exception mais o topo do stack trace, normalizado.
- **Deduplicação com cooldown**: uma investigação por fingerprint a cada janela de cooldown (por exemplo, 10 minutos). As outras 9.999 falhas só incrementam um contador no registro do incidente.

```java
boolean shouldFire(String fingerprint) {
    return cooldownCache.putIfAbsent(fingerprint, now()) == null;
}
```

---

## 5. A captura de evidência (o detalhe que faz ou quebra)

Aqui mora a sutileza mais importante de implementação. O JFR roda num buffer circular com idade máxima. Se você esperar o agente rodar (alguns segundos depois) para ler os eventos, o buffer já girou e a evidência sumiu.

A solução: no instante em que o gatilho dispara, **congele** a janela imediatamente, antes de chamar o agente. O JFR tem o primitivo certo para isso, `takeSnapshot()`, que tira uma foto instantânea do que está no buffer. Você captura agora, analisa depois.

```java
// recording contínua, configurada uma vez no startup
Recording recording = new Recording(Configuration.getConfiguration("profile"));
recording.setMaxAge(Duration.ofMinutes(5));   // buffer rotativo de 5 min
recording.start();

// no disparo do gatilho:
try (Recording snapshot = FlightRecorder.getFlightRecorder().takeSnapshot()) {
    Path evidence = Path.of("/incidents/" + incidentId + ".jfr");
    snapshot.dump(evidence);
    incidentStore.save(incidentId, evidence, failingTraces);
}
```

O pacote de evidência de um incidente contém:

- Os traces que falharam ou ficaram lentos, com os spans e durações, e a exception anexada se for erro.
- O arquivo JFR da janela, de onde se extraem GC, alocação, lock e amostras de CPU.
- Métricas de contexto da JVM no momento: uso de heap, frequência de GC, número de threads.

Isso vira um registro durável (um diretório, ou linhas em Postgres). O agente investiga o registro congelado, de forma assíncrona, sem pressa.

---

## 6. As ferramentas MCP (a interface do agente)

O servidor MCP expõe ferramentas que leem o pacote de evidência e devolvem **agregados**. Cada ferramenta é o portão 2 em ação. O catálogo mínimo:

| Ferramenta | Devolve | Custo de token |
|---|---|---|
| `triage(incidentId)` | Visão geral: tipo do incidente, exception, latência vs baseline, e flags do que parece anômalo (GC alto? lock? downstream lento?) | Baixo. É sempre a primeira chamada. |
| `get_slow_traces(incidentId)` | Os spans mais lentos do trace, qual span dominou a latência | Baixo |
| `get_exception_details(incidentId)` | Stack trace da exception, mensagem, span de origem | Baixo |
| `get_gc_activity(incidentId)` | Pausas de GC na janela: duração, contagem, antes/depois de heap | Médio |
| `get_allocation_hotspots(incidentId)` | Top N call sites por bytes alocados | Médio |
| `get_lock_contention(incidentId)` | Threads bloqueadas em monitor, com stack e tempo de espera | Médio |
| `get_endpoint_baseline(endpoint)` | Comportamento normal do endpoint, para comparar | Baixo |

A `triage` é o coração da economia. Ela roda uma correlação determinística barata (por exemplo, "3 das 5 requisições lentas coincidiram com uma pausa de GC de 800ms") e entrega ao agente uma hipótese inicial. Assim o agente já começa apontado na direção certa e gasta menos turnos.

Definir uma ferramenta com o SDK Java oficial (`io.modelcontextprotocol.sdk:mcp`, GA 1.0.0) é direto: você registra um `McpServerFeatures.SyncToolSpecification` com nome, schema de entrada e o handler que lê o pacote de evidência e devolve o agregado.

---

## 7. O loop do agente

O agente é um loop de function calling. Você já faz exatamente isso no EngrenaTudo com Gemini, então o padrão é familiar.

O system prompt define o papel e o formato de saída. O incidente entra como contexto inicial (já com a hipótese da triagem). O modelo chama ferramentas, raciocina sobre os agregados, eventualmente chama mais ferramentas, e converge para um laudo.

```
System: Você é um analista de causa raiz de JVM. Um incidente disparou no
endpoint {endpoint}. Investigue usando as ferramentas disponíveis. Comece
sempre por triage. Faça drill-down apenas na dimensão que a triagem apontar
como suspeita. Não chame ferramentas de dimensões irrelevantes. Ao concluir,
produza um laudo com: causa, evidência, confiança (alta/média/baixa) e
correção sugerida.

User: Incidente {incidentId}. Tipo: SLOW. Endpoint: POST /checkout.
Latência: 4200ms (baseline p99: 380ms).
```

O loop em pseudocódigo:

```
contexto = [system, incidente]
enquanto não houver laudo final:
    resposta = modelo.gerar(contexto, ferramentas=catalogoMCP)
    se resposta pede ferramenta:
        resultado = servidorMCP.chamar(ferramenta, args)
        contexto += [chamada, resultado]
    senão:
        laudo = resposta
        break
```

### Saída estruturada

Force o laudo num formato fixo para poder rotear e armazenar. Exemplo de saída:

```json
{
  "endpoint": "POST /checkout",
  "tipo": "SLOW",
  "causa_raiz": "Pausa de GC full disparada por alocação excessiva no método InvoiceBuilder.buildLineItems",
  "evidencia": [
    "Pausa de GC de 812ms às 14:03:07, sobrepondo 4 das 5 requisições lentas",
    "buildLineItems responde por 73% das alocações na janela (1.2GB)",
    "Heap após GC voltou a 89%, indicando pressão sustentada"
  ],
  "confianca": "alta",
  "correcao_sugerida": "buildLineItems cria uma lista intermediária por item dentro de um loop sobre N itens. Reutilizar um buffer ou usar stream lazy elimina a alocação quadrática."
}
```

---

## 8. Modos de consumo

As mesmas ferramentas MCP servem dois modos, de graça:

**Modo autônomo (o que você descreveu).** O loop do agente roda no servidor. Gatilho dispara, agente investiga, laudo é empurrado para um sink. O sink pode ser Slack, um dashboard, ou WhatsApp via Evolution API, que combina com a sua stack. Você recebe "o /checkout quebrou por causa de X" sem pedir nada.

**Modo interativo.** Um humano conecta um host MCP (Claude Desktop, por exemplo) e pergunta "o que houve com o /checkout às 14h?". O LLM investiga sob demanda usando as mesmas ferramentas. Útil para investigação manual e pós-morte.

Comece pelo autônomo, que é o seu objetivo. O interativo vem junto.

---

## 9. Stack concreta

| Camada | Tecnologia |
|---|---|
| Gatilho | OpenTelemetry Java SDK, `SpanProcessor` |
| Baseline / dedup | Em memória para v1, Redis quando escalar |
| Captura | JFR programático, `Recording` + `takeSnapshot()` |
| Leitura de JFR | `jdk.jfr.consumer.RecordingFile` sobre o snapshot |
| Armazenamento de incidente | Diretório ou Postgres |
| Servidor MCP | SDK Java `io.modelcontextprotocol.sdk:mcp` 1.0.0 |
| Agente | Gemini function calling (ou Claude) sobre as tools MCP |
| Sink | Slack / WhatsApp (Evolution API) / dashboard |

Tudo Java no v1. Só faz sentido reescrever o servidor MCP em Go mais tarde, se você quiser o binário leve e desacoplado, e nessa fase o agente também sobrevive quando a app morre de OOM.

### Eventos JFR relevantes

- `jdk.GarbageCollection`, `jdk.GCPhasePause`: pausas de GC, antes/depois de heap.
- `jdk.ObjectAllocationSample` (JDK 16+): hotspots de alocação por call site.
- `jdk.JavaMonitorEnter`: contenção de lock, com stack e tempo de espera.
- `jdk.ExecutionSample`: amostras de CPU por método.
- `jdk.JavaExceptionThrow`: exceptions lançadas na janela.

---

## 10. Roadmap por fases

Cada fase entrega algo que funciona de ponta a ponta.

**Fase 0, o circuito fechado.** SpanProcessor que detecta só erro (status ERROR), captura via takeSnapshot, uma única ferramenta `get_exception_details`, e um agente que devolve a stack trace explicada em português. Objetivo: provar o fluxo gatilho até laudo. Tudo Java, sem dedup ainda.

**Fase 1, o controle de tempestade.** Adicione fingerprint, dedup e cooldown. Sem isso você não roda em nada real sem quebrar o orçamento. Essa fase é chata mas é a que viabiliza produção.

**Fase 2, a triagem determinística.** Implemente `triage` com a correlação barata (latência vs GC, vs lock). É o que dá direção ao agente e corta turnos.

**Fase 3, lentidão e as dimensões.** Adicione detecção de lentidão por baseline e as ferramentas `get_slow_traces`, `get_gc_activity`, `get_allocation_hotspots`, `get_lock_contention`. Aqui o sistema passa a responder "lento por causa de X".

**Fase 4, o sink.** Empurre o laudo para onde você vê (WhatsApp, Slack). Vira um SRE de bolso.

**Fase 5, o split de produção.** Tire a investigação de dentro do app. O lado Java (no `-javaagent`) fica só com o leve — gatilho, captura JFR e **extração dos agregados** num `IncidentRecord` JSON durável — e envia esse JSON, por HTTP, a um **serviço de análise separado escrito em Go** que serve as tools MCP (leitores finos sobre o JSON), roda o loop do agente (BYO-LLM) e despacha o laudo pros canais. A fronteira de linguagem casa com a de capacidade: ler JFR é API nativa do Java, então a extração fica no Java; orquestração, loop do agente e dispatch ficam no Go, num binário leve que **sobrevive ao OOM do app**. Contrato completo (schema do JSON, transporte, durabilidade e o que o Go precisa construir) em [GO-ANALYSIS-SERVICE.md](GO-ANALYSIS-SERVICE.md).

**Fase 5.5, o endurecimento de produção.** O circuito fecha na Fase 5, mas três coisas no caminho de captura precisam mudar antes de rodar numa frota real — trate como requisito, não otimização:

1. **Dump do snapshot assíncrono + single-flight.** O `takeSnapshot()` é o *freeze* e é barato (clona referências de chunk); pode ficar síncrono na thread do request junto com a captura do nome da thread. O `dump()`+store, não — ele escreve dezenas/centenas de MB e travaria o request por centenas de ms a segundos. Jogue o dump+extração pra um executor single-thread em background, com um semáforo de 1 (single-flight) pra que uma falha multi-fingerprint não dispare vários dumps concorrentes — tempestade de I/O e de heap justo durante o incidente.
2. **Dedup e baseline em estado compartilhado (Redis).** Em memória, cada pod tem seu cooldown e seu p99: a mesma incidência dispara uma vez **por pod**, não uma vez no cluster, e o portão 1 deixa de capar custo numa frota. O baseline em memória ainda zera a cada deploy e sofre com cardinalidade — exija nomes de span templatizados (rota, não path param cru).
3. **JFR config tunado, não o `profile` de prateleira.** O `profile` é agressivo demais (method sampling a 10ms) e custa 2-3%+ em throughput alto. Monte um `.jfc` enxuto: GC, `ObjectAllocationSample`, `JavaMonitorEnter`, `JavaExceptionThrow` e `ExecutionSample` mais espaçado. E limite o buffer por **`maxSize` além de `maxAge`** — só idade não tampa o footprint.

Fronteira de escopo que vale assumir desde já: JFR é uma JVM só. A RCA responde *"por que ESTA JVM ficou lenta/deu erro"*, com profundidade de método — **não** *"qual dos meus N microsserviços é o culpado"* quando a causa está noutro serviço. É advisor de RCA intra-JVM, com humano no loop; não ator que remedia sozinho.

---

## 11. Riscos e mitigações

**O observador causa OOM.** JFR streaming e snapshots consomem memória. Use buffer com idade e tamanho máximos. Na fase de produção, mova o agente para fora do processo.

**Custo de token escapa.** Os dois portões. Se mesmo assim escapar, é quase sempre porque uma ferramenta está devolvendo dado cru em vez de agregado. Audite o tamanho de cada retorno de tool.

**Falso positivo de lentidão.** Baseline por endpoint, não limiar global. E cooldown para não repetir.

**Alucinação do laudo.** A correlação numérica fica na triagem determinística, não no LLM. O agente narra e prioriza, não calcula. Peça sempre o campo de confiança e a lista de evidências, para o laudo ser auditável.

**Evidência evaporada.** Capture no disparo, não na análise. O takeSnapshot resolve isso.

---

## 12. É possível?

Sim, e nenhuma peça é exótica. JFR já entrega GC, alocação, lock e CPU com overhead baixo. OTel já entrega latência e erro por endpoint. O SDK MCP Java está GA. Function calling você já domina. O trabalho real não está em inventar tecnologia, está em três coisas: o gatilho com controle de tempestade, a triagem determinística que dá direção ao agente, e a disciplina de manter as ferramentas devolvendo agregados.

O diferencial open source do projeto é exatamente esse: pegar primitivas que já existem e expor como uma interface investigativa acionada por evento, em vez de mais um dashboard de alerta.