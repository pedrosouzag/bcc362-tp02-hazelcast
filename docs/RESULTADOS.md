# Resultados e discussão

Rodada final: `resultados/experimentos/final/` (gerada por `python3 scripts/experimentos.py --saida
resultados/experimentos/final` e consolidada por `python3 scripts/analisar.py resultados/experimentos/final`).
Tabelas completas em [RESUMO.md](../resultados/experimentos/final/RESUMO.md), `metricas.csv` e `clientes.csv`.

## Configuração comum

| Item | Valor |
|---|---|
| Hazelcast | 5.4.0 Community, CP Subsystem com `cpMemberCount = 5` |
| Java | OpenJDK 17.0.21 (Ubuntu 24.04 no WSL2), JVMs separadas no mesmo computador |
| Processos | 5 Sync (10 em `alta-n10`), 1 cliente por Sync, 1 observador |
| Pedidos | 20 por cliente (cliente 1 da carga desigual: 50) |
| Seção crítica | sorteada entre 200 e 1000 ms (`SYNC_SEMENTE`) |
| Pausa do cliente | 1–5 s (normal), 0 (alta contenção, com largada conjunta) |
| Sementes | por rodada em `config.json`: clientes 3026/4026/5026 e Sync 3526/4526/5526 nas repetições 1/2/3 |

Espera = `espera_ns` do ACQUIRE, medido no próprio Sync com `System.nanoTime()` (do pedido de
`acquire` até obter o lock). Tempo total = da largada conjunta ao fim do último cliente.

## Resultados por cenário

Com 3 repetições: mediana (mínimo–máximo).

| Cenário | Rep. | Concluídos | Tempo total (s) | Espera mediana (ms) | Espera p90 (ms) | Ocupação máx. |
|---|---|---|---|---|---|---|
| normal | 3 | 300/300 | 84,3 (83,4–86,4) | 196 (180–212) | 1007 (911–1148) | R = 1 |
| alta | 3 | 300/300 | 60,7 (58,5–90,0) | 2420 (2336–2561) | 3035 (2874–3237) | R = 1 |
| desigual | 1 | 130/130 | 93,6 | 685 | 1640 | R = 1 |
| alta-n10 | 1 | 200/200 | 124,9 | 5566 | 6282 | R = 1 |
| sem-lock | 1 | 100/100 | 14,7 | 0 | 0 | **R = 5, 98 sobreposições** |
| semaforo2 | 1 | 100/100 | 64,0 | 1909 | 2546 | R = 1; **R-K2 = 2** |
| semaforo3 | 1 | 100/100 | 63,0 | 1842 | 2463 | R = 1; **R-K3 = 3** |

Todas as rodadas passaram nas checagens automáticas (`propriedades_ok = true`): pedidos completos,
zero pedidos ativos e zero erros no observador; com lock, ocupação máxima 1 e nenhuma sobreposição;
sem lock, sobreposição detectada; no semáforo, pico maior que 1 e nunca acima de K. O verificador
de arquivos (`verificar-logs`) aceitou as rodadas protegidas e rejeitou a rodada sem lock.

## Interpretação

### Exclusão mútua
Com o FencedLock, o observador nunca registrou dois acessos ativos a R (ocupação máxima 1 em
1.130 acessos protegidos). O controle negativo, com a mesma carga e sem lock, levou a 5 acessos
simultâneos e 98 sobreposições: a instrumentação detecta a violação quando ela existe. A
garantia vem do CP Subsystem: o dono do lock é um estado único replicado por Raft, e cada
`lock`/`unlock` só vale depois de confirmado pela maioria dos 5 membros CP.

No semáforo, o pico foi exatamente K (2 e 3), nunca acima, e a etapa de R continuou com
ocupação 1. Mutex e semáforo funcionam juntos sem um interferir no limite do outro.

### Progresso e deadlock
Todos os 1.230 pedidos terminaram com COMMITTED. Não houve deadlock: há um único lock por etapa,
a etapa do semáforo só começa depois de liberar R (nunca se segura um recurso esperando o outro),
e a liberação está em `finally`.

**Caso `alta-r1`.** Na primeira repetição de alta contenção, os 5 primeiros pedidos esperaram
cerca de 27 s (espera máxima 29,4 s; nas outras repetições, cerca de 3,5 s). Nesse intervalo os
Sync registraram rodadas repetidas de eleição Raft (`We are FOLLOWER and there is no current leader`,
12 vezes no Sync 5). Sem líder estável, o grupo CP não confirma operações, então `lock()` fica
bloqueado. Quando o líder se firmou, o lock foi concedido (fences 9, 10, 11...) e a rodada terminou
com 100/100 pedidos e nenhuma sobreposição. É um exemplo real de que o CP Subsystem prefere
**parar a perder a consistência**: a segurança (exclusão mútua) foi mantida, o progresso só foi
adiado. A causa provável é disputa de CPU no início (11 JVMs subindo juntas no mesmo computador) ou uma pausa da VM
do WSL; não investigamos a fundo porque falhas e eleição estão fora do escopo do TP. Por isso o
tempo total da alta contenção é apresentado pela mediana (60,7 s), e o mínimo–máximo mostra o caso.

**Relógio de parede × monotônico (mesma rodada).** No Sync 2, o pedido 2-1 tem `ENTER` em
+28.617 ms e `EXIT` em +28.350 ms pelo relógio de parede: o relógio do sistema foi corrigido para trás
durante o travamento. Pelo `System.nanoTime()` do mesmo log, a sequência está correta
(ENTER +28.617,1 ms, EXIT +29.057,4 ms, diferença igual à duração sorteada de 434 ms + escrita do log).
Por isso a espera é medida com relógio monotônico dentro do processo, e a sobreposição é decidida
pela ordem de chegada no observador, não por horários de processos diferentes.

### Justiça e starvation
- **Alta contenção:** as esperas medianas por cliente ficam entre 2,2 e 2,7 s, e todos os clientes
  fizeram os 20 acessos. Nenhum cliente foi favorecido de forma sistemática.
- **Carga desigual:** o cliente 1, sem pausa, fez 50 acessos com espera mediana de 638 ms; os demais
  ficaram entre 679 e 961 ms e todos concluíram. O cliente rápido ocupa mais o recurso porque pede
  mais vezes, mas não impede os outros de entrar.
- A implementação do Hazelcast 5.4.0 guarda os pedidos pendentes em ordem de chegada ao líder, mas a
  documentação **não promete** justiça FIFO. Ausência de starvation nestas execuções curtas é uma
  **observação experimental, não uma prova**.

### 5 × 10 Sync (`alta` × `alta-n10`, mesma carga por cliente)
A espera mediana passou de 2,4 s para 5,6 s e o tempo total de 60,7 s para 124,9 s, com o dobro de
pedidos (100 → 200). A vazão ficou praticamente igual (1,6 acesso/s): o lock serializa os acessos,
então o limite é a duração da seção crítica (média de 0,59 s), não o número de processos. Cada
Sync a mais é mais um concorrente na mesma fila, e a espera cresce aproximadamente com o número
de concorrentes. Com 10 Sync, o grupo CP continua com 5 membros (os outros 5 são membros comuns),
então o custo de replicação por operação não muda.

### Mensagens por pedido
| Tipo | Mutex | Mutex + semáforo | Como |
|---|---|---|---|
| Aplicação (WRITE/COMMITTED) | 2 | 2 | contado |
| Observador (BEGIN/OK, END/OK) | 4 | 8 | contado; só instrumentação |
| Protocolo Raft | ≈ 20 | ≈ 40 | **estimado** (4G, G = 5), ver [MENSAGENS.md](MENSAGENS.md) |

O avanço do fence (que é o `commitIndex` Raft) entre acessos consecutivos foi de 2,2 entradas de log
por acesso na carga normal, 2,5 na alta contenção e 3,1 com 10 Sync; com a etapa de semáforo, cerca
de 4,5. Cada acesso usa ao menos 2 entradas (lock e unlock), e o restante vem de heartbeats de sessão
CP, que crescem com o número de membros e com o tempo de execução. Esse número conta entradas de
log, não mensagens de rede, e não substitui uma medição do tráfego Raft, que a API não expõe.

## Comparações que não fazemos

- Normal × alta contenção: as pausas mudam a carga; servem para mostrar a diferença de contenção,
  não para dizer qual "é mais rápido".
- Carga desigual × as demais: total de pedidos diferente (130).
- Semáforo × mutex puro: cada pedido passa por duas etapas.

## Limitações

- Todos os processos no mesmo computador (WSL2, 16 CPUs lógicas, `-XX:ActiveProcessorCount=2` por JVM).
- Uma repetição para desigual, 10 Sync, sem lock e semáforos; três para normal e alta, como pede o enunciado.
- Mensagens Raft estimadas, não medidas.
- Sem testes de falha, partição ou recuperação (fora do escopo do TP2).
