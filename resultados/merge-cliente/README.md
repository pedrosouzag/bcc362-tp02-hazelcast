# Validação do merge do cliente configurável

Em 17/11/2026, integração do remoto b8a2329 (incluindo 1340d86) à versão local 0ad822e. Foram mantidos observador, semáforos, validação de confirmações e verificação completa dos eventos. As variáveis do cliente foram incorporadas com validação e prioridade dos argumentos explícitos.

Executado: `bash scripts/build.sh` e `python scripts/test.py`, com JDK 21.0.10, alvo Java 17 e Hazelcast 5.4.0. A suíte terminou com TESTES_OK.

| Cenário | Pedidos confirmados | Máximo R | Máximo semáforo | Violações |
|---|---:|---:|---:|---:|
| Sem lock | 50 | 5 | — | 48, esperadas no controle |
| Mutex | 50 | 1 | — | 0 |
| Mutex + K=2 | 50 | 1 | 2 | 0 |
| Mutex + K=3 | 50 | 1 | 3 | 0 |

Os clientes pares usaram quantidade e pausa por ambiente; os ímpares usaram argumentos para substituir valores diferentes do ambiente. Todos concluíram dez pedidos, sem espera entre eles. A semente dos clientes foi 42; as durações das etapas continuam aleatórias.

Também passaram: padrões e limites da configuração, repetição dos sorteios com semente, rejeição de configurações inválidas, confirmações inválidas, pedidos malformados, observador, um conjunto válido e dez conjuntos inválidos de logs, e as 16 verificações de reentrância/capacidade/devolução de permissões após a carga.

Os logs completos estão em `build/teste-20261117-210624/`. Esta pasta guarda os resumos e o resultado das primitivas. A execução é uma validação funcional do merge, não substitui os experimentos finais com três repetições normal/alta, carga desigual e métricas consolidadas. Nenhum push foi realizado.
