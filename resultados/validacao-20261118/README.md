# Validação antes da apresentação — 18/11/2026

Base: ebd76d3, com roteiro operacional e ajuste do executor para iniciar o Java diretamente pelo JDK no Windows. Ambiente: Windows, JDK 21.0.10, compilação Java 17, Hazelcast 5.4.0. Esta verificação não substitui a rodada final da equipe no WSL, preservada em resultados/experimentos/final.

- Compilação: aprovada.
- `python scripts/test.py`: TESTES_OK; 200 pedidos nos cenários sem lock, mutex, K=2 e K=3. Picos 5 sem proteção, 1 no mutex, 2/3 nos semáforos. Também passaram configuração, respostas inválidas, verificação de logs e primitivas.
- `python scripts/experimentos.py --cenarios alta-n10 --repeticoes 1 --pedidos 10 --saida build/validacao-apresentacao-20261118`: EXPERIMENTOS_OK; dez Sync e dez clientes, 100 pedidos, duração da carga 57,75 s, ocupação máxima 1, zero sobreposições/erros/ativos ao final e verificação offline OK.
- `python scripts/analisar.py build/validacao-apresentacao-20261118`: métricas geradas com sucesso.
- Os processos Java de teste foram encerrados ao final. O início direto pelo JDK evita deixar JVMs filhas do atalho Oracle no Windows.

Resumos nesta pasta; logs completos em build/teste-20261118-201407 e build/validacao-apresentacao-20261118. O procedimento para a apresentação está em docs/COMO_APRESENTAR.md. Não foi feita nova revisão visual ou compilação dos slides nesta validação.
