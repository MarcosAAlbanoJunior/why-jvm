# Profundidade do RCA — de "traduz o stack trace" a diagnóstico

O laudo bom não traduz o stack trace ("NPE na linha 48" qualquer um vê). Ele
**raciocina**: diz a causa provável, **descarta** as alternativas com evidência,
e (quando dá) aponta o método e o porquê no nível do código. Este doc mapeia as
camadas de profundidade — o que faz o projeto se destacar — em ordem de
impacto/risco.

> Princípio que rege tudo: **cada afirmação é ancorada num número medido, não num
> palpite do LLM.** A correlação determinística mora na triagem; o agente narra e
> prioriza, não inventa. É o que torna a confiança honesta.

---

## Tier 1 — Hipóteses descartadas (diagnóstico diferencial) ✅ *(em implementação)*

O formato que mais eleva a percepção, com o menor risco: o laudo lista o que
**não** é a causa, cada item com o sinal que o descarta.

```
Hipóteses descartadas
  ✓ Pausas de GC — irrelevantes (maior 4ms)
  ✓ Contenção de lock / deadlock — sem espera em monitor
  ✓ CPU — a thread do request não estava em CPU
  ✓ Pressão de alocação — 143 MB amostrados, mas JVM-wide e a thread não
    estava em CPU (ruído de fundo, não a causa)
  ✓ Espera externa (I/O / banco / downstream) — 10s em Thread.sleep → AQUI

Causa mais provável: espera externa (a thread dormiu 10s).
```

**Por que é honesto (não alucinação):** a triagem já mede cada dimensão. Descartar
é determinístico — `longestGcPauseMs < 10% da latência` → GC fora; `totalLockWaitMs ~0`
→ lock fora; `threadActivity.cpuSamples == 0` → CPU fora; etc. E **codifica a lição
JVM-wide vs thread do request**: alocação/GC somam todas as threads, então alocação
só é causa se a thread do request esteve em CPU (uma pausa de GC, sendo
stop-the-world, afeta todos — essa não se descarta por espera).

**Por que justifica a confiança:** "alta (92%)" passa a significar *descartei 6
coisas com evidência*, não *o LLM sentiu*. O diferencial é o que dá lastro ao número.

**Fonte dos dados:** `triageSignals` + `dimensions.threadActivity` (já existem).
`jvmContext` (heap) entra quando for populado dos MXBeans — até lá, heap fica fora
do diferencial. Sem snapshot JFR, não se descarta nada (honesto).

---

## Tier 2 — Code-aware RCA (ler o fonte do método suspeito) ✅ *(concluído)*

Transforma *onde* (linha 48) em *por quê* **com evidência**. O agente lê o fonte do
método no topo do stack (pelo símbolo `Classe.metodo(Arquivo.java:linha)`) e confirma
o bug no nível do código:

```
NPE em CustomerService.calculateDiscount (linha 48)
→ o fonte mostra: customer.getTier() sem checar Optional.empty()
→ customer veio de CustomerRepository.findById(), que retorna vazio quando não acha
Causa: findById retornou vazio e calculateDiscount não validou Optional.empty()
Correção: validar antes de acessar customer.
```

Sem o fonte, essa narrativa seria **palpite plausível** — exatamente o que o projeto
combate. Com o fonte, é o diferencial mais forte: *nomeia o método E explica o bug*.
Ninguém combina isso. Esforço médio (um tool que lê `arquivo:linha` do repo + repo map).

---

## Tier 3 — `get_slow_traces` (árvore do trace) ✅ *(concluído)*

A captura da árvore de spans do trace (filhos, não só o span que disparou), com
tempo por span. Destrava:

- **"findById() = 71% do tempo gasto"** — qual span/método dominou a latência.
- **Detecção de N+1** — N spans de JDBC idênticos num request (precisa dos spans de
  query, que o agente OTel gera no caminho `-javaagent`).

Já está marcado como pendente desde a Fase 3 (ver `INSTRUCTION.md`). Legal, vem
depois do Tier 1/2.

---

## Norte (parqueado — NÃO é foco agora) 🌟 Correlação com deploy / histórico

O "wow" máximo: *"o timeout começou 2 min após o deploy 1.8.4"*, *"P95 180ms → 4.2s"*,
exaustão de pool correlacionada a uma versão. Mas isso **não é um tweak, é um
subsistema**: exige rastrear deploys/versões + histórico de incidentes (store em
Postgres) + correlação temporal. É a visão de longo prazo do produto, deliberadamente
fora do escopo atual — registrado aqui pra não se perder.

---

## Ordem

1. **Tier 1 — hipóteses descartadas** ✅. Maior impacto/risco, dados prontos.
2. **Tier 2 — code-aware RCA** ✅. O diferencial mais forte.
3. **Tier 3 — get_slow_traces** ✅. Destrava "% do tempo" e N+1.
4. **Norte** — correlação com deploy/histórico. Só depois do Postgres + feed de deploy.
