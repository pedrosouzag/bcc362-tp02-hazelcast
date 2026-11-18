# Resumo dos experimentos — validacao-apresentacao-20261118

Gerado por `scripts/analisar.py`. Espera medida no Sync com `System.nanoTime()`; tempo total medido
pelo script, da largada conjunta ao fim do último cliente. Seção crítica sorteada em 200–1000 ms.
**Não comparar diretamente cenários com cargas ou durações diferentes**; com várias repetições,
o valor é a mediana (mínimo–máximo).

## Por cenário

| cenario | repeticoes | syncs | concluidos | tempo_total_s | vazao_por_s | espera_mediana_ms | espera_p90_ms | resposta_mediana_ms | ocupacao_max_R | ocupacao_max_semaforo | entradas_raft_por_acesso_obs | propriedades_ok |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| alta-n10 | 1 | 10 | 100/100 | 57.75 | 1.732 | 5117.8 | 5978.8 | 5710.0 | 1 | — | 2.98 | True |

## Por rodada

| rodada | concluidos | esperados | tempo_total_s | espera_mediana_ms | espera_max_ms | espera_semaforo_mediana_ms | secao_critica_media_ms | ocupacao_max_R | sobreposicoes_R | semente_clientes | semente_sync | propriedades_ok |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| alta-n10-r1 | 100 | 100 | 57.75 | 5117.8 | 6530.4 | — | 562.9 | 1 | 0 | 3026 | 3526 | True |

## Mensagens por pedido

Aplicação (WRITE/COMMITTED) e observador (BEGIN/OK e END/OK por etapa) são contadas pelo protocolo da
aplicação. Protocolo Raft estimado em 4G = 20 por etapa protegida (G = 5 membros CP); não é
exposto pela API. `entradas_raft_por_acesso_obs` = avanço médio do fence entre acessos consecutivos
(o fence é o commitIndex Raft; inclui heartbeats de sessão e unlocks).

| rodada | msg_aplicacao_por_pedido | msg_observador_por_pedido | msg_protocolo_estimada_por_pedido | entradas_raft_por_acesso_obs |
|---|---|---|---|---|
| alta-n10-r1 | 2 | 4 | 20 | 2.98 |

## Por cliente (acessos e espera)

| rodada | cliente | pedidos | acessos | espera_mediana_ms | espera_max_ms | resposta_mediana_ms |
|---|---|---|---|---|---|---|
| alta-n10-r1 | 1 | 10 | 10 | 4931.1 | 6436.2 | 5524.5 |
| alta-n10-r1 | 2 | 10 | 10 | 5433.2 | 5909.5 | 5993.0 |
| alta-n10-r1 | 3 | 10 | 10 | 4809.7 | 6108.9 | 5673.0 |
| alta-n10-r1 | 4 | 10 | 10 | 5177.3 | 6423.8 | 5861.0 |
| alta-n10-r1 | 5 | 10 | 10 | 5055.6 | 6530.4 | 5498.5 |
| alta-n10-r1 | 6 | 10 | 10 | 5193.1 | 5917.2 | 5582.0 |
| alta-n10-r1 | 7 | 10 | 10 | 5131.9 | 6080.6 | 5815.0 |
| alta-n10-r1 | 8 | 10 | 10 | 5148.2 | 5764.2 | 5707.5 |
| alta-n10-r1 | 9 | 10 | 10 | 5099.1 | 6098.4 | 5695.0 |
| alta-n10-r1 | 10 | 10 | 10 | 4907.1 | 6019.5 | 5429.0 |
