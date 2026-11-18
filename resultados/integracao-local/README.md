# Validação da integração local

Executada em 17/11/2026, integrando o remoto a45e272 com o trabalho local 7eec525 e a etapa nova de semáforo. Windows 11, JDK 21.0.10, alvo Java 17 e Hazelcast 5.4.0. Nenhuma publicação no GitHub foi feita nesta etapa.

Cada cenário iniciou cinco Sync, cinco clientes socket e um observador novo. Dez pedidos por cliente, sem pausa entre pedidos; duração aleatória de 200–1000 ms por etapa. Nos modos de semáforo, cada pedido usa R, libera o mutex e depois executa a etapa de capacidade limitada.

| Cenário | Pedidos confirmados | Máximo em R | Máximo no semáforo | Violações em R | Violações no semáforo |
|---|---:|---:|---:|---:|---:|
| Sem lock, controle negativo | 50 | 5 | — | 48 | — |
| Mutex | 50 | 1 | — | 0 | — |
| Mutex + K=2 | 50 | 1 | 2 | 0 | 0 |
| Mutex + K=3 | 50 | 1 | 3 | 0 | 0 |

Total: 200 pedidos confirmados e 300 etapas simuladas, incluindo as 100 etapas de semáforo. Todos os cenários terminaram sem ocupantes ativos ou erros de instrumentação. As violações do controle negativo são esperadas. Os valores de ocupação e sobreposição podem variar em novas execuções.

Também foram verificados:

- Rejeição de confirmação com ID errado, ERROR e EOF pelo cliente.
- Rejeição de comando diferente de WRITE, cliente incorreto, pedido incompleto e sequência zero pelo Sync.
- Detecção de BEGIN duplicado, repetição de pedido já encerrado, END sem BEGIN e extrapolação de K=2/K=3 pelo observador, sem usar o observador como bloqueio.
- Ordem dos eventos por pedido e verificação offline dos cenários protegidos; rejeição do cenário sem proteção.
- Um conjunto válido de logs aprovado e dez conjuntos alterados rejeitados: vazio, participante ausente, pedido trocado, saída duplicada, tempo monotônico invertido, fence trocado, evento desconhecido, confirmação ausente, observador vazio e sobreposição no observador.
- Após a carga K=3, 16 verificações das primitivas: reentrância com o mesmo fence, bloqueio do concorrente até a última liberação, capacidade de K=2/K=3, compartilhamento das permissões e restauração das permissões. São dois clientes Hazelcast de diagnóstico, além dos clientes da aplicação já encerrados.

Procedimento: `bash scripts/build.sh`, `python scripts/test.py`. Nesta execução, a regressão dos arquivos também foi chamada separadamente com `python scripts/test_logs.py build/teste-20261117-205830/com-lock/registros`; esse passo agora integra o script principal.

Esta pasta contém resumo, regressões, teste das primitivas e logs selecionados (observador completo e Sync 1 de cada cenário). Os arquivos de todos os Sync e clientes estão em `build/teste-20261117-205830/`. Para executar verificar-logs, usar a subpasta `registros/` de um cenário completo via LOG_DIR, não esta seleção de evidências.

A integração mantém os arquivos Java da aplicação diretamente em src e não usa Docker. O código de diagnóstico está em tests. Permanecem pendentes os experimentos finais com sementes, três repetições normal/alta, carga desigual, métricas consolidadas, comparação 5/10 quando viável e apresentação. Estes testes funcionais não substituem essa avaliação experimental.
