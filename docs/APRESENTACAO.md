# Apresentação — TP2 B5 Hazelcast

> Ajustes para a versão integrada: iniciar o observador **antes dos Sync**; instrumentação usa **4 mensagens por etapa**, incluindo ACK de END. A estimativa 4G é apenas um modelo, não tráfego medido. O teste com 10 participantes e os valores de RAM/tempo citados abaixo precisam de evidências reproduzíveis antes da apresentação. O exemplo de fences precisa ser vinculado ao arquivo de log correspondente. Não apresentar fila interna como garantia pública de justiça/FIFO. Usar `resultados/` para evidências; os slides LaTeX e as métricas finais ainda estão pendentes.

Seminário de **25 min**, slides de **10 a 15** (sem contar referências e logs).
Conteúdo dos slides, roteiro de fala, divisão entre os integrantes e roteiro da demonstração.
Os números de resultados entram depois dos experimentos (marcados com `[preencher]`).

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
Exemplo real (5 syncs, alta contenção):
```
sync 1  pedido 1-1  ENTER fence 7   ... EXIT
sync 2  pedido 2-1  ENTER fence 8   ... EXIT
sync 4  pedido 4-1  ENTER fence 9   ... EXIT
sync 5  pedido 5-1  ENTER fence 10  ... EXIT
sync 3  pedido 3-1  ENTER fence 11  ... EXIT
sync 1  pedido 1-2  ENTER fence 13
```
- Por que o 2 esperou: o lock tinha dono (sync 1); o pedido do 2 ficou na fila.
- O `unlock` do 1 entrega o lock ao primeiro da fila → novo fence, maior.
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
- Sem proteção: observador acusa sobreposições `[preencher: nº de sobreposições, pico]`.
- Com FencedLock: **0 sobreposições** em todas as rodadas `[preencher]`.
- Semáforo: pico de ocupação = K, nunca acima (K = 2: `[preencher]`; K = 3: `[preencher]`).

### 10. Resultados: espera e justiça
Tabela por experimento (3 repetições de carga normal e de alta contenção):
pedidos concluídos, tempo total, **mediana da espera**, acessos por cliente `[preencher]`.
Carga desigual: acessos e espera do cliente rápido × demais `[preencher]`.

> Fala: não comparamos diretamente experimentos com cargas ou durações diferentes.

### 11. Resultados: 5 × 10 syncs e mensagens
- 10 syncs é viável numa máquina: 10 JVMs, ~2,1 GB de RAM, CP formado em ~20 s;
  5 membros CP + 5 comuns.
- Mensagens por entrada (ver `docs/MENSAGENS.md`): aplicação 2, observador 3,
  protocolo ≈ 4G = 20 (estimado). Com 10 syncs, o custo do protocolo **não cresce**
  (G continua 5); Ricart–Agrawala iria de 8 para 18.
- Espera mediana 5 × 10 `[preencher]`.

### 12. Discussão
- **Exclusão mútua:** garantida pelo Raft (um dono no estado replicado); verificada pelo observador.
- **Progresso:** todo pedido terminou com COMMITTED; `unlock` em `finally`.
- **Deadlock:** um único recurso e liberação garantida → sem espera circular. Com R1 e R2 seria
  preciso uma ordem global de aquisição.
- **Starvation:** a fila do lock é por ordem de chegada no líder; nas rodadas, todos os clientes
  foram atendidos. Ausência de starvation numa execução curta é **observação, não prova**.
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
