# Resumo dos experimentos — final

Gerado por `scripts/analisar.py`. Espera medida no Sync com `System.nanoTime()`; tempo total medido
pelo script, da largada conjunta ao fim do último cliente. Seção crítica sorteada em 200–1000 ms.
**Não comparar diretamente cenários com cargas ou durações diferentes**; com várias repetições,
o valor é a mediana (mínimo–máximo).

## Por cenário

| cenario | repeticoes | syncs | concluidos | tempo_total_s | vazao_por_s | espera_mediana_ms | espera_p90_ms | resposta_mediana_ms | ocupacao_max_R | ocupacao_max_semaforo | entradas_raft_por_acesso_obs | propriedades_ok |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| normal | 3 | 5 | 300/300 | 84.3 (83.435–86.383) | 1.2 (1.158–1.199) | 195.7 (179.6–211.8) | 1006.5 (910.7–1147.7) | 931.5 (871.0–962.5) | 1 (1–1) | — | 2.2 (2.17–2.26) | True |
| alta | 3 | 5 | 300/300 | 60.7 (58.509–90.018) | 1.6 (1.111–1.709) | 2419.9 (2336.1–2561.2) | 3035.2 (2874.4–3237.2) | 3032.0 (2954.5–3225.5) | 1 (1–1) | — | 2.5 (2.52–2.52) | True |
| desigual | 1 | 5 | 130/130 | 93.557 | 1.39 | 685.0 | 1640.0 | 1352.0 | 1 | — | 2.25 | True |
| alta-n10 | 1 | 10 | 200/200 | 124.919 | 1.601 | 5565.5 | 6281.5 | 6248.0 | 1 | — | 3.1 | True |
| sem-lock | 1 | 5 | 100/100 | 14.658 | 6.822 | 0.0 | 0.0 | 654.5 | 5 | — | — | True |
| semaforo2 | 1 | 5 | 100/100 | 64.007 | 1.562 | 1908.6 | 2545.6 | 3162.5 | 1 | 2 | 4.45 | True |
| semaforo3 | 1 | 5 | 100/100 | 62.96 | 1.588 | 1842.0 | 2462.5 | 3109.0 | 1 | 3 | 4.49 | True |

## Por rodada

| rodada | concluidos | esperados | tempo_total_s | espera_mediana_ms | espera_max_ms | espera_semaforo_mediana_ms | secao_critica_media_ms | ocupacao_max_R | sobreposicoes_R | semente_clientes | semente_sync | propriedades_ok |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| normal-r1 | 100 | 100 | 86.383 | 179.6 | 2236.4 | — | 588.3 | 1 | 0 | 3026 | 3526 | True |
| normal-r2 | 100 | 100 | 83.435 | 195.7 | 2207.5 | — | 549.4 | 1 | 0 | 4026 | 4526 | True |
| normal-r3 | 100 | 100 | 84.257 | 211.8 | 1822.0 | — | 550.1 | 1 | 0 | 5026 | 5526 | True |
| alta-r1 | 100 | 100 | 90.018 | 2561.2 | 29377.6 | — | 588.3 | 1 | 0 | 3026 | 3526 | True |
| alta-r2 | 100 | 100 | 58.509 | 2336.1 | 3273.3 | — | 549.4 | 1 | 0 | 4026 | 4526 | True |
| alta-r3 | 100 | 100 | 60.661 | 2419.9 | 3497.8 | — | 550.1 | 1 | 0 | 5026 | 5526 | True |
| desigual-r1 | 130 | 130 | 93.557 | 685.0 | 2400.8 | — | 583.5 | 1 | 0 | 3026 | 3526 | True |
| alta-n10-r1 | 200 | 200 | 124.919 | 5565.5 | 7063.4 | — | 587.1 | 1 | 0 | 3026 | 3526 | True |
| sem-lock-r1 | 100 | 100 | 14.658 | 0.0 | 0.1 | — | 588.3 | 5 | 98 | 3026 | 3526 | True |
| semaforo2-r1 | 100 | 100 | 64.007 | 1908.6 | 3221.8 | 1.5 | 583.7 | 1 | 0 | 3026 | 3526 | True |
| semaforo3-r1 | 100 | 100 | 62.96 | 1842.0 | 2845.3 | 1.6 | 583.7 | 1 | 0 | 3026 | 3526 | True |

## Mensagens por pedido

Aplicação (WRITE/COMMITTED) e observador (BEGIN/OK e END/OK por etapa) são contadas pelo protocolo da
aplicação. Protocolo Raft estimado em 4G = 20 por etapa protegida (G = 5 membros CP); não é
exposto pela API. `entradas_raft_por_acesso_obs` = avanço médio do fence entre acessos consecutivos
(o fence é o commitIndex Raft; inclui heartbeats de sessão e unlocks).

| rodada | msg_aplicacao_por_pedido | msg_observador_por_pedido | msg_protocolo_estimada_por_pedido | entradas_raft_por_acesso_obs |
|---|---|---|---|---|
| normal-r1 | 2 | 4 | 20 | 2.2 |
| normal-r2 | 2 | 4 | 20 | 2.17 |
| normal-r3 | 2 | 4 | 20 | 2.26 |
| alta-r1 | 2 | 4 | 20 | 2.52 |
| alta-r2 | 2 | 4 | 20 | 2.52 |
| alta-r3 | 2 | 4 | 20 | 2.52 |
| desigual-r1 | 2 | 4 | 20 | 2.25 |
| alta-n10-r1 | 2 | 4 | 20 | 3.1 |
| sem-lock-r1 | 2 | 4 | 0 | — |
| semaforo2-r1 | 2 | 8 | 40 | 4.45 |
| semaforo3-r1 | 2 | 8 | 40 | 4.49 |

## Por cliente (acessos e espera)

| rodada | cliente | pedidos | acessos | espera_mediana_ms | espera_max_ms | resposta_mediana_ms |
|---|---|---|---|---|---|---|
| normal-r1 | 1 | 20 | 20 | 187.9 | 1421.7 | 806.0 |
| normal-r1 | 2 | 20 | 20 | 104.1 | 1747.8 | 1003.5 |
| normal-r1 | 3 | 20 | 20 | 237.6 | 2236.4 | 1022.5 |
| normal-r1 | 4 | 20 | 20 | 2.1 | 1192.5 | 859.0 |
| normal-r1 | 5 | 20 | 20 | 421.5 | 1316.9 | 1110.5 |
| normal-r2 | 1 | 20 | 20 | 37.8 | 2207.5 | 793.5 |
| normal-r2 | 2 | 20 | 20 | 245.8 | 1403.5 | 839.0 |
| normal-r2 | 3 | 20 | 20 | 84.1 | 1219.9 | 949.0 |
| normal-r2 | 4 | 20 | 20 | 195.7 | 1641.1 | 809.0 |
| normal-r2 | 5 | 20 | 20 | 256.4 | 1262.6 | 879.5 |
| normal-r3 | 1 | 20 | 20 | 149.3 | 1790.3 | 1007.0 |
| normal-r3 | 2 | 20 | 20 | 52.1 | 1449.6 | 863.0 |
| normal-r3 | 3 | 20 | 20 | 564.2 | 1822.0 | 1128.0 |
| normal-r3 | 4 | 20 | 20 | 350.4 | 1254.5 | 1097.0 |
| normal-r3 | 5 | 20 | 20 | 26.7 | 1156.6 | 831.0 |
| alta-r1 | 1 | 20 | 20 | 2664.1 | 29047.6 | 3300.5 |
| alta-r1 | 2 | 20 | 20 | 2561.2 | 28565.9 | 3249.0 |
| alta-r1 | 3 | 20 | 20 | 2554.7 | 29377.6 | 3290.0 |
| alta-r1 | 4 | 20 | 20 | 2462.0 | 27857.1 | 3140.5 |
| alta-r1 | 5 | 20 | 20 | 2510.9 | 27233.9 | 3156.5 |
| alta-r2 | 1 | 20 | 20 | 2370.9 | 3273.3 | 2976.0 |
| alta-r2 | 2 | 20 | 20 | 2465.1 | 2970.7 | 2962.5 |
| alta-r2 | 3 | 20 | 20 | 2174.8 | 2874.4 | 2861.5 |
| alta-r2 | 4 | 20 | 20 | 2447.9 | 3246.7 | 2932.0 |
| alta-r2 | 5 | 20 | 20 | 2330.4 | 3077.4 | 3061.5 |
| alta-r3 | 1 | 20 | 20 | 2463.8 | 3497.4 | 3032.0 |
| alta-r3 | 2 | 20 | 20 | 2239.2 | 3038.3 | 3143.5 |
| alta-r3 | 3 | 20 | 20 | 2463.3 | 3413.7 | 3024.5 |
| alta-r3 | 4 | 20 | 20 | 2528.5 | 3176.1 | 3117.0 |
| alta-r3 | 5 | 20 | 20 | 2370.1 | 3497.8 | 2987.5 |
| desigual-r1 | 1 | 50 | 50 | 638.0 | 2171.3 | 1236.5 |
| desigual-r1 | 2 | 20 | 20 | 745.5 | 2062.7 | 1342.0 |
| desigual-r1 | 3 | 20 | 20 | 961.0 | 2003.4 | 1521.0 |
| desigual-r1 | 4 | 20 | 20 | 703.0 | 1933.1 | 1280.5 |
| desigual-r1 | 5 | 20 | 20 | 678.6 | 2400.8 | 1270.0 |
| alta-n10-r1 | 1 | 20 | 20 | 5609.1 | 6838.5 | 6326.0 |
| alta-n10-r1 | 2 | 20 | 20 | 5632.5 | 6376.5 | 6269.5 |
| alta-n10-r1 | 3 | 20 | 20 | 5469.8 | 6612.3 | 6136.5 |
| alta-n10-r1 | 4 | 20 | 20 | 5423.0 | 7063.4 | 6083.0 |
| alta-n10-r1 | 5 | 20 | 20 | 5560.0 | 6817.6 | 6292.0 |
| alta-n10-r1 | 6 | 20 | 20 | 5591.9 | 6583.3 | 6335.5 |
| alta-n10-r1 | 7 | 20 | 20 | 5514.9 | 6624.2 | 6000.0 |
| alta-n10-r1 | 8 | 20 | 20 | 5643.0 | 6388.3 | 6357.5 |
| alta-n10-r1 | 9 | 20 | 20 | 5681.4 | 6505.9 | 6303.0 |
| alta-n10-r1 | 10 | 20 | 20 | 5595.5 | 6281.5 | 6156.5 |
| sem-lock-r1 | 1 | 20 | 20 | 0.0 | 0.0 | 455.0 |
| sem-lock-r1 | 2 | 20 | 20 | 0.0 | 0.0 | 612.5 |
| sem-lock-r1 | 3 | 20 | 20 | 0.0 | 0.0 | 746.5 |
| sem-lock-r1 | 4 | 20 | 20 | 0.0 | 0.0 | 669.5 |
| sem-lock-r1 | 5 | 20 | 20 | 0.0 | 0.1 | 663.0 |
| semaforo2-r1 | 1 | 20 | 20 | 2181.1 | 3047.3 | 3223.5 |
| semaforo2-r1 | 2 | 20 | 20 | 1986.9 | 2985.5 | 3141.5 |
| semaforo2-r1 | 3 | 20 | 20 | 1595.5 | 3221.8 | 2979.0 |
| semaforo2-r1 | 4 | 20 | 20 | 1723.4 | 2756.1 | 3193.5 |
| semaforo2-r1 | 5 | 20 | 20 | 1996.7 | 2545.6 | 3058.0 |
| semaforo3-r1 | 1 | 20 | 20 | 1983.6 | 2737.3 | 3086.0 |
| semaforo3-r1 | 2 | 20 | 20 | 2086.2 | 2670.1 | 2984.5 |
| semaforo3-r1 | 3 | 20 | 20 | 1735.6 | 2503.6 | 3179.5 |
| semaforo3-r1 | 4 | 20 | 20 | 1756.4 | 2845.3 | 3126.0 |
| semaforo3-r1 | 5 | 20 | 20 | 1890.5 | 2681.4 | 3118.0 |
