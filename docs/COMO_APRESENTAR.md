# Como executar e explicar a apresentação

Use Java 17 ou superior, Python 3 e Git Bash no Windows (ou Ubuntu/WSL). Abra os terminais na pasta do repositório. Não misture comandos Bash com PowerShell. Os exemplos abaixo são para Git Bash/Ubuntu; se `python3` não existir no Git Bash, use `python`.

## 1. Preparar antes da aula

```bash
git pull --ff-only
java -version
python3 --version
bash scripts/build.sh
python3 scripts/test.py
```

Espere `TESTES_OK`. Não execute a suíte junto com a demonstração: ambas usam as portas 5701–5710, 6001–6010 e 7000. Abra previamente `slides/apresentacao.pdf`, `docs/RESULTADOS.md` e `resultados/experimentos/final/RESUMO.md`. Os números da rodada final são da execução registrada pela equipe no WSL, não uma promessa do desempenho de outra máquina.

## 2. Demonstração automática, mais simples

Em um único terminal:

```bash
python3 scripts/experimentos.py --cenarios sem-lock,alta,semaforo2,semaforo3 --repeticoes 1 --pedidos 10
```

O script inicia observador, cinco Sync e cinco clientes para cada cenário, aguarda prontidão, libera os clientes juntos, verifica os resultados, salva os arquivos e encerra os processos. São processos independentes mesmo aparecendo em um terminal. Aguarde vários minutos: o período de formação do cluster também faz parte do tempo da demonstração, mas é separado da medida da carga.

Anote a pasta que aparece em `Saída:` e consolide:

```bash
python3 scripts/analisar.py resultados/experimentos/AAAAmmdd-HHMMSS
```

Substitua o caminho pelo impresso na sua execução. Não use `--saida resultados/experimentos/final`: essa pasta já existe e contém as evidências finais. Os processos escrevem a saída detalhada em `<cenario>-r1/saida/` e os eventos em `<cenario>-r1/registros/`.

Para acompanhar enquanto roda, abra um segundo terminal e use o caminho real da rodada:

```bash
tail -f resultados/experimentos/AAAAmmdd-HHMMSS/alta-r1/saida/observador.out
```

Ctrl+C nesse segundo terminal encerra apenas o acompanhamento. Deixe o executor terminar normalmente.

## 3. O que explicar em cada cenário

| Cenário | O que acontece | O que mostrar |
|---|---|---|
| sem-lock | Acesso simulado sem adquirir o mutex | Ocupação maior que 1 e violações. A falha da verificação é esperada: prova que o observador detecta o problema |
| alta | Cinco clientes disputam o mesmo lock R sem pausas entre pedidos | 50 pedidos concluídos, ocupação máxima 1, nenhuma sobreposição |
| semaforo2 | Cada pedido primeiro usa R; libera o mutex e depois pede uma permissão do semáforo | R continua limitado a 1; na etapa R-K2 há concorrência, mas nunca mais de 2 |
| semaforo3 | Mesmo fluxo, com três permissões | R continua limitado a 1; R-K3 permite até 3. O pico pode ficar abaixo de 3 em uma execução curta |

Fala sugerida: “O cliente conhece somente o seu Sync. Quem disputa a autorização é o Sync, usando o Hazelcast. O observador só registra quem entrou e saiu; ele não impede uma entrada. COMMITTED significa que a simulação terminou, não que um dado foi persistido.”

## 4. Execução manual para mostrar os eventos ao vivo

Para este roteiro manual, use quatro terminais Ubuntu/WSL, com Java instalado dentro do WSL. No Windows nativo, prefira o executor automático acima, que encerra as JVMs diretamente. Não execute os dois modos ao mesmo tempo. Os comandos abaixo fazem uma rodada de mutex com cinco clientes.

Terminal 1, observador:

```bash
export LOG_DIR=logs/demo-mutex
bash scripts/run.sh observador
```

Terminal 2, cinco Sync em segundo plano (continue usando esse terminal para encerrá-los):

```bash
export LOG_DIR=logs/demo-mutex HZ_BIND=127.0.0.1 SYNC_SEMENTE=2026
for i in 1 2 3 4 5; do bash scripts/run.sh sync "$i" & done
```

Espere os cinco `SERVIDOR_PRONTO`. `MEMBRO_PRONTO` é o membro iniciado; `CP_PRONTO` informa que o subsistema CP está formado; só `SERVIDOR_PRONTO` indica atendimento aos clientes.

Terminal 3, clientes:

```bash
export CLIENTE_SEMENTE=42
for i in 1 2 3 4 5; do bash scripts/run.sh cliente "$i" 10 0 & done
wait
```

Esse laço aproxima os inícios; para os experimentos com largada conjunta controlada, use o executor automático. Espere cinco mensagens `CLIENTE_CONCLUIDO`.

Terminal 4, depois dos clientes terminarem:

```bash
export LOG_DIR=logs/demo-mutex
bash scripts/run.sh verificar
bash scripts/run.sh verificar-logs 10 5
bash scripts/run.sh reentrancia
```

Reentrância exige o cluster ainda ligado e sem carga. Mostrar duas aquisições com mesmo fence, uma liberação parcial que mantém outro concorrente bloqueado, a liberação final e `REENTRANCIA_OK`.

Para encerrar os Sync, no terminal 2:

```bash
jobs -p | xargs -r kill
wait
```

Esse comando atua nos trabalhos iniciados naquele terminal. No terminal 1, pressione Ctrl+C para encerrar o observador. Não mate todos os processos Java do computador.

Para outra rodada, use uma pasta nova e reinicie todos os processos. Acrescente `sem-lock`, `semaforo2` ou `semaforo3` ao comando de cada Sync. Para consultar semáforos, use `verificar2` ou `verificar3`; para verificar arquivos use `verificar-logs 10 5 2` ou `verificar-logs 10 5 3`.

## 5. Como ler os eventos

- REQUEST: o pedido chegou e vai disputar autorização.
- ACQUIRE: autorização obtida; `espera_ns` é o tempo de espera no próprio Sync.
- ENTER / EXIT: início e fim do trabalho simulado.
- RELEASE: lock ou permissão devolvido.
- COMMITTED: operação concluída; o cliente confere o identificador recebido.
- BEGIN / END no observador: ocupação registrada por recurso; zero ativos ao terminar significa que não restou acesso aberto.
- Fence: token associado à aquisição do mutex. Pode pular números; não é contador de mensagens de rede. No semáforo o campo é zero.

## 6. Resultados e limites da conclusão

Apresente as três repetições normal/alta já registradas, a carga desigual, a comparação 5/10 e a extensão. Compare a espera e os acessos por cliente. Não prometa FIFO ou ausência absoluta de starvation com base em uma execução curta. Separe mensagens da aplicação, instrumentação e estimativas do protocolo interno.

O projeto já contém código, verificadores, experimentos finais, análise e slides. Antes de declarar a entrega encerrada, confira os nomes dos integrantes, a versão final dos slides e ensaie a divisão das falas. Os testes atuais demonstram as propriedades nos cenários executados; não constituem prova de correção para qualquer ambiente.
