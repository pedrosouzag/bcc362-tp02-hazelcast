# Custo de mensagens por entrada em R

O enunciado pede "mensagens por entrada, quando instrumentáveis", separando as mensagens do
**protocolo** das de **instrumentação**. Neste trabalho há três tipos de mensagem:

| Tipo | Quem troca | Instrumentável? |
|---|---|---|
| Aplicação | cliente ↔ seu ClusterSync (socket) | Sim, contamos no nosso código |
| Instrumentação | ClusterSync ↔ observador (socket) | Sim, contamos no nosso código |
| Protocolo | ClusterSync ↔ grupo Raft do CP Subsystem | **Não** pela API pública: estimativa teórica |

## 1. Aplicação (contada)

Por pedido: `WRITE <id>` e `COMMITTED <id>` = **2 mensagens**. O cliente abre uma conexão TCP
por pedido; o handshake TCP não é contado como mensagem da aplicação.

## 2. Instrumentação (contada)

Por pedido: `BEGIN`, `ACK do BEGIN` e `END` = **3 mensagens** (se o observador também confirmar o
END, são 4). Elas existem só para verificação: não fazem parte do mecanismo de sincronização e
não podem ser somadas ao custo do protocolo.

## 3. Protocolo (estimado)

Cada ClusterSync é um membro Hazelcast. O FencedLock `R` vive no grupo CP `default`, replicado
por **Raft** entre os `G = 5` membros CP (`cpMemberCount = 5`, `group-size` padrão = 5).

Cada operação no lock (`lock` e `unlock`) vira **uma entrada no log Raft**:

1. O sync envia a operação ao **líder** do grupo e recebe a resposta: 2 mensagens (0 se o próprio
   sync for o líder).
2. O líder replica a entrada para os `G − 1 = 4` seguidores (`AppendRequest`) e recebe 4 respostas:
   `2(G − 1) = 8` mensagens. A entrada é confirmada quando a maioria (3 de 5) gravou.

Uma entrada em R = `lock` + `unlock` = 2 entradas Raft:

```
custo ≈ 2 × [2 + 2(G − 1)] = 4G  →  G = 5: 20 mensagens  (16 se o sync for o líder)
```

Quando o lock está ocupado, o pedido fica na **fila de espera** do lock, dentro da máquina de
estados Raft. O `unlock` seguinte já entrega o lock ao primeiro da fila; não há mensagem extra
de "agora é sua vez" além da resposta da operação `lock` que estava pendente.

O semáforo (`acquire` + `release`) tem o mesmo custo: **20 mensagens** por uso.

### O que o Hazelcast faz que muda a conta

- **Lote (batching):** sob alta contenção, o líder junta até 100 entradas num mesmo
  `AppendRequest` (`append-request-max-entry-count = 100`). O custo real por entrada tende a ser
  **menor** que 20 com muitos pedidos simultâneos.
- **Tráfego de fundo, independente dos pedidos:**
  - heartbeat da **sessão CP** de cada membro a cada 5 s (`session-heartbeat-interval-seconds = 5`).
    Cada heartbeat também é uma entrada Raft, com o mesmo custo de replicação;
  - heartbeat do líder Raft aos seguidores a cada 5 s (`leader-heartbeat-period = 5000 ms`).

  Esse tráfego deve ser apresentado à parte, como custo fixo por segundo, e não por entrada.

### Por que o fence "pula" números

Conferido no código do Hazelcast 5.4.0 (`com.hazelcast.cp.internal.datastructures.lock.Lock`): o
fence devolvido por `lockAndGetFence()` é o **`commitIndex` da entrada Raft** que concedeu o lock.
Como heartbeats de sessão, `unlock`s e operações do semáforo também ocupam posições do log, os
fences são **crescentes, mas não consecutivos** (ex.: 7, 8, 9, 10, 11, **13**). O que importa para a
exclusão mútua é o fence nunca diminuir.

## 4. Comparação com a trilha de algoritmos

| Mecanismo | Mensagens por entrada | Cresce com |
|---|---|---|
| Coordenador central | 3 | — |
| Lamport | 3(N − 1) | N processos |
| Ricart–Agrawala | 2(N − 1) | N processos |
| Hazelcast FencedLock (Raft) | ≈ 4G (+ fundo) | **G membros CP**, não N |

Com 5 syncs: Ricart–Agrawala = 8 e Hazelcast ≈ 20. Com 10 syncs: Ricart–Agrawala = 18, e o
Hazelcast **continua ≈ 20**, porque o grupo CP segue com 5 membros (os outros 5 syncs entram como
membros comuns). O Hazelcast paga mais por entrada para ter **tolerância a falhas** (o estado do
lock está replicado), coisa que os algoritmos da trilha A não oferecem. O TP não testa falhas; o
ponto é explicar de onde vem o custo.

## 5. Limitações da medição

- As mensagens Raft não são expostas pela API pública. Os números da seção 3 são
  **estimativas a partir do protocolo e das configurações padrão** do Hazelcast 5.4.0, não medições.
- Medimos as mensagens de aplicação e de instrumentação, e o número de entradas Raft pode ser
  aproximado pelo avanço do fence (`fence final − fence inicial`), que inclui o tráfego de fundo.
