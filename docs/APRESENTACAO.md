# Apresentação — TP2 B5 Hazelcast

> Ajustes para a versão integrada: iniciar o observador **antes dos Sync**; instrumentação usa **4 mensagens por etapa**, incluindo ACK de END. A estimativa 4G é apenas um modelo, não tráfego medido. Resolvido: 10 participantes e métricas finais vêm de `resultados/experimentos/final/` (sem valores de RAM); o exemplo de fences aponta para `alta-r2`; a fila interna não é apresentada como garantia FIFO. Pendente: os slides LaTeX.

Seminário de **25 min**, slides de **10 a 15** (sem contar referências e logs).
Conteúdo dos slides, roteiro de fala, divisão entre os integrantes e roteiro da demonstração.
Números dos slides 9–12 tirados de `resultados/experimentos/final/` (discussão completa em
[RESULTADOS.md](RESULTADOS.md)).

## Divisão do tempo e das falas

| Etapa | Tempo | Slides | Quem fala |
|---|---|---|---|
| Problema e primitiva | 3 min | 1–3 | Integrante A |
| Funcionamento interno | 6 min | 4–7 | Integrante B |
| Demonstração | 8 min | 8 | Integrante C (A opera os terminais) |
| Resultados | 3 min | 9–11 | Integrante A |
| Discussão | 5 min | 12–13 | Todos (cada um responde sobre a sua parte) |

Regra do enunciado: **todos falam e todos sabem responder**. Cada integrante deve conseguir
explicar sozinho os slides 4–7 (é o que mais pesa: 25% da nota).

---

## Slides

### 1. Título
TP2 — Sincronização em Sistemas Distribuídos · Trilha B, opção B5: Hazelcast ·
BCC362 · Prof. Joubert Lima · nomes dos integrantes.

### 2. O problema
- Recurso hipotético **R**: só um processo pode "escrever" por vez (exclusão mútua).
- 5 clientes, 5 processos do Cluster Sync, processos independentes falando pela rede.
- Cada cliente faz 10–50 pedidos com `request_id = cliente-seq`.
- Propriedade desejada: **nunca dois acessos simultâneos a R**, e todo pedido termina
  (progresso).

> Fala: "COMMITTED aqui só quer dizer que o acesso simulado terminou; não há dado gravado."

### 3. A primitiva e a versão
- **FencedLock** (mutex distribuído) e **ISemaphore** (K permissões), do **CP Subsystem**.
- CP Subsystem **habilitado**: `cpMemberCount = 5` → grupo Raft com 5 membros.
- **Versão 5.4.0 Community.** A partir da 5.5 o CP Subsystem é Enterprise; testamos:
  - 5.6.0 Community: o membro não sobe (`CP subsystem is a licensed feature`);
  - 5.4.0 Community: FencedLock, reentrância e semáforo funcionam.
- Sem CP habilitado, o lock roda em "unsafe mode" (sem Raft), o que não atende o enunciado.

### 4. Arquitetura
Diagrama: `Cliente i —(socket 6000+i)→ ClusterSync i (membro Hazelcast 5700+i)`, os 5 syncs
formando o grupo CP/Raft, e todos enviando `BEGIN/END` ao **Observador** (fora do Hazelcast).

- Cada cliente conhece **só o seu** sync.
- Quem disputa o lock são os **syncs**, não os clientes.
- Fluxo de um pedido:
  `WRITE → acquire(R, id) → BEGIN (espera ACK) → seção crítica 0,2–1 s → END → release(R, id) → COMMITTED`.
- O observador **só registra e acusa**: não autoriza, não bloqueia, não é mutex.

### 5. FencedLock por dentro
Estado mantido no grupo Raft (conferido no código do Hazelcast 5.4.0):
- **dono** = (sessão CP do membro, thread);
- **contador de reentrância** (`lockCount`);
- **fila de espera** em ordem de chegada (`LinkedHashMap` de pedidos pendentes);
- **fence** = `commitIndex` da entrada Raft que concedeu o lock.

Cada `lock`/`unlock` é uma entrada no log Raft: o líder replica para os outros 4 e confirma
com maioria (3 de 5). Por isso **todos os membros enxergam o mesmo dono**.

### 6. Uma disputa concreta (do nosso log)
Exemplo real (5 syncs, alta contenção, `resultados/experimentos/final/alta-r2/registros/`):
```
sync 1  pedido 1-1  ENTER fence 7   ... EXIT
sync 5  pedido 5-1  ENTER fence 8   ... EXIT
sync 4  pedido 4-1  ENTER fence 9   ... EXIT
sync 3  pedido 3-1  ENTER fence 10  ... EXIT
sync 2  pedido 2-1  ENTER fence 11  ... EXIT
sync 1  pedido 1-2  ENTER fence 13
```
- Os 5 pedidos chegaram juntos (largada conjunta); o sync 1 pegou o lock primeiro.
- Por que o 5 esperou: o lock tinha dono (sync 1); o pedido do 5 ficou pendente no grupo CP.
- O `unlock` do 1 libera o lock para um pendente → novo fence, maior.
- O fence pula de 11 para 13: heartbeats de sessão e unlocks também ocupam posições do log.
  Crescente, não consecutivo.

### 7. Reentrância, mutex × semáforo
- **Reentrância:** a mesma thread pega `R` duas vezes → `lockCount` 1 → 2, **mesmo fence**;
  precisa soltar duas vezes. Outra thread continua bloqueada.
- **Mutex × semáforo:**

| | FencedLock (mutex) | ISemaphore (K) |
|---|---|---|
| Quantos dentro | 1 | até K |
| Dono | sim (sessão + thread) | não; permissões presas à sessão CP |
| Fence | sim | não |
| Reentrante | sim | não se aplica |
| Uso no TP | escrita em R | etapa de capacidade limitada (K = 2 e 3) |

### 8. Demonstração (8 min)
Ver o roteiro abaixo. No slide: só a lista dos 4 passos e os comandos.

### 9. Resultados: exclusão mútua
- Sem proteção (mesma carga): observador acusa **98 sobreposições**, até **5** acessos simultâneos.
- Com FencedLock: **0 sobreposições** e ocupação máxima 1 em todas as 10 rodadas protegidas (1.130 acessos).
- Semáforo: pico de ocupação = K, nunca acima (K = 2: pico **2**; K = 3: pico **3**); R continua com 1.
- Fonte: `resultados/experimentos/final/RESUMO.md`.

### 10. Resultados: espera e justiça
| Cenário | Rep. | Concluídos | Tempo total | Espera mediana |
|---|---|---|---|---|
| normal (pausa 1–5 s) | 3 | 300/300 | 84,3 s (83,4–86,4) | 196 ms (180–212) |
| alta (sem pausa) | 3 | 300/300 | 60,7 s (58,5–90,0) | 2420 ms (2336–2561) |
| desigual | 1 | 130/130 | 93,6 s | 685 ms |

- Alta contenção: medianas por cliente entre 2,2 e 2,7 s; todos fizeram os 20 acessos.
- Desigual: cliente 1 (sem pausa) fez 50 acessos, mediana 638 ms; demais 679–961 ms, todos concluíram.
- `alta-r1` (90 s): eleição de líder Raft na partida segurou os primeiros pedidos ~27 s; segurança
  mantida, progresso adiado (ver slide 12).

> Fala: não comparamos diretamente experimentos com cargas ou durações diferentes.

### 11. Resultados: 5 × 10 syncs e mensagens
- 10 syncs rodou numa máquina (`alta-n10-r1`): 200/200 pedidos em 124,9 s, 0 sobreposições;
  5 membros CP + 5 comuns.
- Mensagens por entrada (ver `docs/MENSAGENS.md`): aplicação 2, observador 4,
  protocolo ≈ 4G = 20 (estimado). Com 10 syncs, o custo do protocolo **não cresce**
  (G continua 5); Ricart–Agrawala iria de 8 para 18.
- Espera mediana 5 × 10 (mesma carga por cliente): **2,4 s → 5,6 s**; vazão igual (~1,6 acesso/s),
  porque o lock serializa e o limite é a seção crítica (média 0,59 s).
- Fence avançou 2,2–2,5 entradas Raft por acesso com 5 Sync e 3,1 com 10 (lock + unlock + heartbeats de sessão).

### 12. Discussão
- **Exclusão mútua:** garantida pelo Raft (um dono no estado replicado); verificada pelo observador.
- **Progresso:** os 1.230 pedidos terminaram com COMMITTED; `unlock` em `finally`. No `alta-r1`, sem
  líder Raft estável por ~27 s, o `lock()` ficou bloqueado: o CP prefere parar a perder consistência.
- **Relógio:** no mesmo `alta-r1`, o relógio de parede do Sync 2 voltou ~0,7 s (EXIT "antes" do ENTER);
  o `nanoTime` ficou correto. Por isso medimos espera com relógio monotônico e decidimos sobreposição
  pelo observador.
- **Deadlock:** um único recurso e liberação garantida → sem espera circular. Com R1 e R2 seria
  preciso uma ordem global de aquisição.
- **Starvation:** nas rodadas, todos os clientes foram atendidos e as esperas por cliente ficaram
  próximas. A implementação guarda os pendentes por ordem de chegada, mas a documentação não promete
  FIFO. Ausência de starvation numa execução curta é **observação, não prova**.
- **Limitações:** tudo num computador; mensagens Raft só estimadas; sem teste de falhas (fora do escopo).

### 13. Perguntas prováveis (para estudar, não precisa virar slide)
- Por que não usar `synchronized` ou `ReentrantLock`? (só valem dentro de uma JVM)
- O que acontece se o dono do lock morrer? (a sessão CP expira e o lock é liberado; fora do escopo)
- Para que serve o fence? (um recurso externo recusaria escrita com fence menor que o último visto)
- Por que 5 membros CP e não 3? (tolera 2 falhas em vez de 1; maioria 3 de 5)
- Por que o observador não é um mutex? (ele só conta; quem decide é o FencedLock)
- Por que 5.4.0? (licença do CP a partir da 5.5)

Referências (slide final, fora da contagem): enunciado TP2; documentação Hazelcast 5.4
(FencedLock, ISemaphore, configuração do CP Subsystem); Ongaro e Ousterhout, *In Search of an
Understandable Consensus Algorithm (Raft)*, 2014.

---

## Roteiro da demonstração (8 min)

Preparar antes: projeto compilado (`bash scripts/build.sh`), `logs/` vazia, 4 terminais
(syncs, observador, clientes, verificador) com fonte grande.

1. **Subir o cluster (1 min).** Os 5 syncs: mostrar `AGUARDANDO_CP` até
   `CP_PRONTO ... membrosCP=5`. Subir o observador.
2. **Sem proteção (2 min).** Rodar os clientes no modo sem lock com alta contenção. Mostrar o
   observador acusando sobreposição. Rodar o verificador: **falha**, como esperado.
3. **Com FencedLock (3 min).** Mesma carga com lock. Mostrar no log `REQUEST → ACQUIRE → ENTER
   (fence) → EXIT → RELEASE → COMMITTED` e um sync esperando enquanto outro está dentro.
   Verificador: **OK, 0 sobreposições**.
4. **Reentrância e semáforo (2 min).** Comando de reentrância (`lockCount` 1 → 2, mesmo fence).
   Semáforo K = 2: observador mostra pico 2, nunca 3.

Plano B: se algo travar ao vivo, mostrar os logs salvos da rodada equivalente em `results/`.
