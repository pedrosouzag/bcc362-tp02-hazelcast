# TP02 — Sincronização em Sistemas Distribuídos (Hazelcast)

Para apresentar: [roteiro de execução e explicação dos eventos](docs/COMO_APRESENTAR.md).

BCC362 — Sistemas Distribuídos | Prof. Joubert Lima

Trabalho feito sobre o [TP01](https://github.com/cardosoAM/bcc362-tp01-hazelcast), seguindo a **Trilha B, opção B5 (Hazelcast)** do enunciado.

## Versão do Hazelcast: 5.4.0 (Community)

O enunciado pede conferir edição e licença antes de usar o Hazelcast. O TP01 usava a 5.6.0, mas a partir da **5.5** o CP Subsystem passou a ser recurso **Enterprise** (pago). Testamos:

| Versão | Edição | `cpMemberCount = 3` | Resultado |
|---|---|---|---|
| 5.6.0 | Community | membro não sobe | `IllegalStateException: CP subsystem is a licensed feature. Please ensure you have an Enterprise license that enables CP.` |
| 5.4.0 | Community | sobe com 3 membros CP | FencedLock (com reentrância, mesmo fence) e ISemaphore funcionaram |

Por isso fixamos a **5.4.0**, a última versão Community com CP Subsystem. Assim não é preciso chave de licença (nem credencial no repositório), e qualquer pessoa reproduz com `bash scripts/build.sh`. A alternativa seria a licença trial Enterprise de 30 dias, que expira e exige chave para cada pessoa.

## Objetivo

Sincronizar o acesso de vários clientes a um recurso hipotético **R**, usando as primitivas do CP Subsystem do Hazelcast:

- **FencedLock**: exclusão mútua sobre R (disputa, aquisição, liberação e reentrância).
- **Semáforo distribuído** (`ISemaphore`): etapa de capacidade limitada, para mostrar a diferença entre mutex e semáforo.

## Cenário (Cluster Sync)

- Pelo menos 5 clientes e 5 processos independentes (JVMs separadas).
- Cada cliente faz de 10 a 50 pedidos de escrita em R, cada um com um `request_id` único (`client_id`, número sequencial).
- Fluxo de cada pedido: `acquire(R, request_id)` → seção crítica (sleep de 0,2 a 1 s) → `release(R, request_id)` → `COMMITTED`.
- O cliente espera de 1 a 5 s antes do próximo pedido.
- Logs e verificação automática de que nunca há dois clientes na seção crítica ao mesmo tempo.

## Estrutura

```
src/        código Java
lib/        hazelcast-5.4.0.jar (SHA-256 em lib/SHA256SUMS)
scripts/    build.sh e run.sh
pom.xml     build com Maven (opcional)
```

## Como executar

Requisitos: Java 17+ (Ubuntu/WSL ou Linux).

```bash
bash scripts/build.sh
```

Primeiro iniciar o observador (porta 7000), em um terminal:

```bash
bash scripts/run.sh observador
```

Subir os 5 processos do Cluster Sync (um terminal para cada; portas Hazelcast 5701 a 5705 e atendimento 6001 a 6005):

```bash
HZ_BIND=127.0.0.1 bash scripts/run.sh sync 1   # ... até sync 5
```

Cada processo imprime `MEMBRO_PRONTO`, depois `CP_PRONTO ... membrosCP=5` e finalmente `SERVIDOR_PRONTO`. Esperar os cinco servidores ficarem prontos antes de iniciar os clientes.

Iniciar cinco clientes, cada um em seu processo (trocar o ID de 1 até 5):

```bash
bash scripts/run.sh cliente 1
```

Cada cliente conhece somente `127.0.0.1:6000+ID`. O padrão continua sendo 10 a 50 pedidos e intervalo aleatório de 1 a 5 segundos. Para a demonstração com disputa, iniciar os cinco clientes juntos usando dez pedidos e nenhuma pausa entre eles:

```bash
bash scripts/run.sh cliente 1 10 0   # repetir para IDs 2, 3, 4 e 5
```

Os argumentos opcionais são `cliente ID [pedidos] [intervalo-ms]`: pedidos entre 10 e 50 (ou 0 para sortear); intervalo fixo entre 0 e 5000 ms. O padrão de carga normal continua sendo 1–5 s; intervalos menores servem aos experimentos. A duração de cada acesso a R permanece aleatória entre 200 e 1000 ms em qualquer modo. O cliente rejeita erro, conexão encerrada e confirmação de outro pedido.

Também é possível configurar o cliente por ambiente, conforme a atualização da equipe:

| Variável | Padrão | Significado |
|---|---|---|
| `CLIENTE_PEDIDOS` | `0` | 0 sorteia 10–50; valores fixos devem estar entre 10 e 50 |
| `CLIENTE_ESPERA_MIN_MS` | `1000` | limite inferior da pausa |
| `CLIENTE_ESPERA_MAX_MS` | `5000` | limite superior; exige 0 ≤ mínimo ≤ máximo ≤ 5000 |
| `CLIENTE_SEMENTE` | `-1` | -1 sorteia uma semente e a imprime (`semente=... (sorteada)`); outro inteiro fixa a semente. O cliente usa semente + ID |
| `CLIENTE_INICIO_MS` | — | instante (epoch em ms) da largada conjunta; o cliente espera até ele antes do primeiro pedido |

Argumentos explícitos de quantidade e intervalo têm prioridade sobre as variáveis correspondentes. A semente é definida por ambiente. Exemplo de alta contenção (repetir para os cinco IDs):

```bash
CLIENTE_PEDIDOS=10 CLIENTE_ESPERA_MIN_MS=0 CLIENTE_ESPERA_MAX_MS=0 CLIENTE_SEMENTE=42 bash scripts/run.sh cliente 1
```

Para carga desigual, usar intervalo zero em apenas um cliente e manter os demais com seus intervalos normais. A semente repete os sorteios do cliente, não a ordem de escalonamento da rede nem as durações sorteadas no Sync; a reprodução completa dos experimentos ainda exige configurar essas durações.

Após todos os clientes terminarem, consultar o observador:

```bash
bash scripts/run.sh verificar
```

Com cinco clientes de dez pedidos, esperar `entradas=50 saidas=50 ativos=0 maximo=1 sobreposicoes=0 erros=0`. Esse comando consulta os contadores; o teste automatizado abaixo verifica as condições e retorna erro se alguma falhar.

Encerrar os Sync e o observador com Ctrl+C. Reiniciar o observador para cada experimento, para começar com contadores vazios. A execução local manual usa 5 JVMs de clientes, 5 de Sync e 1 observador; não requer Docker.

## Como a sincronização foi implementada

`Main` configura o cluster CP e inicia `ClusterSync`. Cada Sync abre um servidor socket e processa os pedidos de seu cliente sequencialmente. A concorrência ocorre entre os processos Sync, que disputam o mesmo `FencedLock("R")`. A integração preserva os logs em arquivo e o fence da implementação da equipe; o atendimento local permanece sequencial, suficiente para um cliente por Sync.

O fluxo é `REQUEST → acquire → ACQUIRE → BEGIN/OK → ENTER → sleep → EXIT → END/OK → release → RELEASE → COMMITTED`. A aquisição e a liberação estão separadas da simulação, e o lock é liberado em `finally`, na mesma thread que o adquiriu. No log, `espera_ns` mede a espera pela aquisição com relógio monotônico do próprio processo.

`Verificador` recebe BEGIN e END e mantém o conjunto de pedidos ativos. Ele confirma imediatamente o registro mesmo quando detecta sobreposição: não espera o recurso ficar livre nem autoriza acessos. O Sync aguarda o registro de BEGIN antes do sleep e de END antes de liberar o lock. Assim a verificação não depende de comparar relógios físicos de máquinas diferentes. BEGIN duplicado e END sem BEGIN são erros de instrumentação.

## Controle negativo e teste automático

Para mostrar que o observador detecta acessos simultâneos, iniciar **todos** os Sync com o argumento `sem-lock`, mantendo o CP e a instrumentação:

```bash
HZ_BIND=127.0.0.1 bash scripts/run.sh sync 1 sem-lock   # repetir até ID 5
```

Executar os cinco clientes com `10 0`. O observador deve registrar `VIOLACAO`, `maximo` maior que 1 e `sobreposicoes` maior que zero. Neste controle, ACQUIRE/RELEASE marcam o ponto do fluxo em que a proteção seria aplicada, mas o log traz `protegido=false` e nenhuma aquisição de lock é feita. Depois encerrar os processos, iniciar um observador novo e repetir sem o argumento `sem-lock`.

O teste automatizado faz esse procedimento completo, além de validar respostas incorretas e erros do observador. Requer **Python 3**, usa somente sua biblioteca padrão e precisa das portas 5701–5705, 6001–6005 e 7000 livres:

```bash
bash scripts/build.sh
python3 scripts/test.py
```

No Windows, também é possível executar `python scripts/test.py` após compilar pelo Git Bash. O script inicia e encerra apenas os processos que criou, usa um nome de cluster exclusivo, grava logs em `build/teste-AAAAmmdd-HHMMSS/` e termina com `TESTES_OK` somente se:

- os clientes rejeitarem confirmação com ID errado, ERROR e EOF;
- o observador detectar sobreposição e mensagens inconsistentes;
- cada cenário concluir 50 pedidos, com os seis eventos na ordem esperada por pedido;
- o cenário sem lock apresentar sobreposição e o cenário protegido tiver ocupação máxima 1;
- não houver pedidos ativos nem erros de instrumentação ao final dos cenários.

Este é um teste funcional inicial. Não substitui as três repetições de carga normal/alta, carga desigual, métricas completas e extensão do enunciado.

Os [resultados da validação inicial](resultados/validacao-inicial/README.md) incluem o resumo e os logs selecionados dos dois cenários.

A [validação da integração e dos semáforos](resultados/integracao-local/README.md) registra os quatro cenários, as regressões do verificador e os testes das primitivas executados após integrar a atualização da equipe.

## Etapa de semáforo e verificação dos arquivos

Para K=2, iniciar um observador novo e os cinco Sync usando o mesmo modo:

```bash
bash scripts/run.sh observador
HZ_BIND=127.0.0.1 bash scripts/run.sh sync 1 semaforo2   # repetir até ID 5
```

Depois iniciar os cinco clientes com `cliente ID 10 0`. Cada pedido faz a etapa R com mutex, libera o lock e só então adquire uma permissão de `R-K2`. A segunda etapa também simula trabalho entre 200 e 1000 ms, devolve a permissão em `finally` e termina antes de COMMITTED. Não há mutex envolvendo a etapa do semáforo.

Para K=3, encerrar todos os processos, iniciar um observador novo e usar `semaforo3` em todos os Sync. Não misturar modos na mesma execução. Consultar as contagens de cada recurso com:

```bash
bash scripts/run.sh verificar    # R: limite 1
bash scripts/run.sh verificar2   # R-K2: limite 2
bash scripts/run.sh verificar3   # R-K3: limite 3
```

Nos resumos, `sobreposicoes` conta entradas acima do limite do recurso. Duas entradas simultâneas no semáforo K=2 são válidas. Ao final, esperar 50 entradas/saídas por etapa usada, zero ativos, zero erros e zero violações. Na etapa do semáforo deve existir uso concorrente (máximo maior que 1 e até K).

Os logs ficam em `logs/`, ou na pasta definida por `LOG_DIR` em todos os processos. Cada Sync recria seu arquivo ao iniciar. Use pasta nova para cada experimento e não execute duas experiências na mesma pasta. Formato:

```text
hora_ms;sync;pedido;evento;fence;recurso;tempo_monotonico_ns;detalhe
```

O fence é o token retornado por `lockAndGetFence()`; no semáforo é zero. O relógio monotônico só é comparado dentro do mesmo Sync. O observador grava seus BEGIN/END em ordem no arquivo `observador.log`, confirmando o registro antes de responder.

Após os clientes terminarem, verificar a execução fixa de dez pedidos por cliente:

```bash
bash scripts/run.sh verificar-logs 10 5       # apenas mutex
bash scripts/run.sh verificar-logs 10 5 2     # mutex + semáforo K=2
bash scripts/run.sh verificar-logs 10 5 3     # mutex + semáforo K=3
```

Escolher somente o comando correspondente ao experimento. O verificador retorna 1 para arquivos vazios, participantes ausentes, formato antigo/inválido, pedidos trocados, eventos ausentes/duplicados/fora de ordem, fence inconsistente, ordem monotônica invertida ou ocupação inválida no observador. Exige a quantidade informada para cada cliente; para esta conferência, iniciar os clientes com quantidade fixa. Logs antigos ENTROU/SAIU não são aceitos como evidência completa: gerar uma execução nova.

O teste `python3 scripts/test.py` agora cobre quatro cenários: sem lock, mutex, mutex+K2 e mutex+K3, com 50 pedidos por cenário. Também executa regressões com logs alterados e `tests/PrimitivasTest.java`. Esse teste adicional usa dois clientes Hazelcast de diagnóstico após a carga: confere reentrância, bloqueio até a segunda liberação, limite das permissões e devolução das permissões pela aplicação. Esses clientes de diagnóstico não substituem os cinco clientes socket do cenário do trabalho.

Os registros e resumos de cada cenário ficam na pasta `build/teste-*/`. As regressões de arquivos podem ser repetidas com `python3 scripts/test_logs.py CAMINHO_DOS_REGISTROS_DO_CENARIO_COM_LOCK`.

Configuração por variável de ambiente:

| Variável | Padrão | Uso |
|---|---|---|
| `HZ_CLUSTER` | `tp02-sync` | nome do cluster |
| `HZ_MEMBERS` | `127.0.0.1:5701` … `:5705` | membros para o join TCP (10 processos: listar as 10 portas) |
| `HZ_CP_MEMBROS` | `5` | `cpMemberCount` do CP Subsystem (mínimo 3) |
| `HZ_BIND` | — | interface de rede (`127.0.0.1` para rodar local) |
| `OBS_HOST` | `127.0.0.1` | endereço do observador usado pelos Sync e pela consulta |
| `OBS_PORT` | `7000` | porta do observador |
| `LOG_DIR` | `logs` | pasta dos registros da execução; deve ser nova ou estar reservada ao experimento |
| `SYNC_SEMENTE` | `-1` | semente das durações da seção crítica; -1 sorteia e imprime em `SYNC_CONFIG`. O Sync usa semente + ID |

Com 10 Sync, gerar a lista de membros com `export HZ_MEMBERS=$(bash scripts/membros.sh 10)`; o `cpMemberCount` continua 5 (5 membros CP e 5 membros comuns).

## Demonstração de reentrância

Com os 5 Sync rodando e **sem carga** (nenhum cliente ativo):

```bash
bash scripts/run.sh reentrancia
```

O comando conecta ao cluster como cliente Hazelcast de diagnóstico (não é um dos 5 clientes do cenário), pega `R` duas vezes na mesma thread e mostra `lockCount` 1 → 2 com o **mesmo fence**, uma outra thread bloqueada enquanto o contador não volta a 0, e uma nova aquisição com fence maior. Termina com `REENTRANCIA_OK`.

## Experimentos e métricas

```bash
bash scripts/build.sh
python3 scripts/experimentos.py                         # todos os cenários (~30 min)
python3 scripts/analisar.py resultados/experimentos/AAAAmmdd-HHMMSS
```

| Cenário | Sync | Carga | Repetições |
|---|---|---|---|
| `normal` | 5 | pausa 1–5 s entre pedidos | 3 |
| `alta` | 5 | sem pausa, largada conjunta | 3 |
| `desigual` | 5 | cliente 1 sem pausa e com 50 pedidos; demais com pausa 1–5 s | 1 |
| `alta-n10` | 10 | igual a `alta`, com 10 Sync e 10 clientes | 1 |
| `sem-lock` | 5 | `alta` sem FencedLock (controle negativo) | 1 |
| `semaforo2` / `semaforo3` | 5 | `alta` com a etapa de semáforo K=2 / K=3 | 1 |

Opções: `--cenarios alta,normal`, `--repeticoes N`, `--pedidos N` (10–50, padrão 20 por cliente), `--semente N`. A quantidade de pedidos é fixa por cliente para que as repetições de um cenário tenham a mesma carga. Cada rodada usa cluster, observador e pasta de logs novos, fixa as sementes do cliente e do Sync, faz a largada conjunta e grava em `resultados/experimentos/AAAAmmdd-HHMMSS/<cenario>-r<N>/`:

- `config.json`: cenário, Sync, pedidos, pausas, duração da seção crítica, sementes, versões;
- `resumo.json`: tempo total, contagens do observador, `verificar-logs` e `propriedades_ok`;
- `registros/`: logs de cada Sync e do observador; `saida/`: saída de cada processo.

O script confere as propriedades pelo observador: zero sobreposição e ocupação máxima 1 com lock, sobreposição detectada sem lock, nunca mais que K e uso concorrente no semáforo, e todos os pedidos concluídos. O `analisar.py` gera `RESUMO.md`, `metricas.csv` e `clientes.csv` com pedidos concluídos, tempo total, espera mediana/p90/máxima pelo lock (`espera_ns`, relógio monotônico do Sync), tempo de resposta no cliente, acessos e espera por cliente, e mensagens por pedido (aplicação e observador contadas; Raft estimado, ver [docs/MENSAGENS.md](docs/MENSAGENS.md)).

## Resultados

Rodada final em [`resultados/experimentos/final/`](resultados/experimentos/final/RESUMO.md): 11 rodadas (normal e alta contenção com 3 repetições; desigual, 10 Sync, sem lock e semáforos K=2/K=3), todas com as propriedades verificadas. Interpretação (exclusão mútua, progresso, deadlock, justiça/starvation, 5 × 10 Sync, mensagens) em [docs/RESULTADOS.md](docs/RESULTADOS.md).

## Pendências da entrega completa

- Produzir os slides a partir de [docs/APRESENTACAO.md](docs/APRESENTACAO.md) e ensaiar a demonstração.

## Referência

- [Hazelcast 5.4 — FencedLock](https://docs.hazelcast.com/hazelcast/5.4/data-structures/fencedlock)
- [Hazelcast 5.4 — Configuração do CP Subsystem](https://docs.hazelcast.com/hazelcast/5.4/cp-subsystem/configuration)
- [Hazelcast 5.6 — CP Subsystem como recurso Enterprise](https://docs.hazelcast.com/hazelcast/5.6/cp-subsystem/cp-subsystem)
