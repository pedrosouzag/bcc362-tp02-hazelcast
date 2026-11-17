# Validação funcional inicial

Execução em 17/11/2026, Windows 11, Java 21.0.10, Hazelcast 5.4.0; compilação com alvo Java 17. Comando: `python scripts/test.py`, depois de `bash scripts/build.sh`.

Cada cenário iniciou cinco JVMs Sync, cinco JVMs de clientes e um observador novo. Cada cliente enviou dez pedidos, sem intervalo entre eles; o acesso simulado manteve a duração aleatória entre 200 e 1000 ms.

| Cenário | Pedidos concluídos | Ocupação máxima | Entradas com sobreposição | Ativos no final | Erros |
|---|---:|---:|---:|---:|---:|
| Sem lock (controle negativo) | 50 | 5 | 49 | 0 | 0 |
| Com FencedLock | 50 | 1 | 0 | 0 | 0 |

O teste também conferiu as dez confirmações por cliente e a sequência REQUEST, ACQUIRE, ENTER, EXIT, RELEASE e COMMITTED de cada pedido. Os clientes rejeitaram ID de confirmação incorreto, ERROR e EOF. Um teste separado do observador injetou BEGIN duplicado e END sem BEGIN: os dois erros em `resumo.json`, na seção `observador`, são esperados nesse teste de protocolo, não nos cenários completos.

Os logs selecionados mostram todos os BEGIN/END observados nos dois cenários. `sobreposicoes` conta entradas registradas enquanto outro pedido estava ativo, não pares de pedidos conflitantes. Uma sobreposição deve ser detectada no controle negativo; seu valor exato pode mudar entre execuções.

Este resultado comprova o fluxo funcional inicial e a instrumentação. Não substitui as três repetições de carga normal e alta, a carga desigual, as métricas consolidadas ou os experimentos de semáforo ainda pendentes. Os logs completos de novas execuções ficam em `build/teste-*/`.
