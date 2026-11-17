# TP02 — Sincronização em Sistemas Distribuídos (Hazelcast)

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

Os argumentos opcionais são `cliente ID [pedidos] [intervalo-ms]`: pedidos entre 10 e 50; intervalo 0 ou entre 1000 e 5000 ms. A duração de cada acesso a R permanece aleatória entre 200 e 1000 ms em qualquer modo. O cliente rejeita erro, conexão encerrada e confirmação de outro pedido.

Após todos os clientes terminarem, consultar o observador:

```bash
bash scripts/run.sh verificar
```

Com cinco clientes de dez pedidos, esperar `entradas=50 saidas=50 ativos=0 maximo=1 sobreposicoes=0 erros=0`. Esse comando consulta os contadores; o teste automatizado abaixo verifica as condições e retorna erro se alguma falhar.

Encerrar os Sync e o observador com Ctrl+C. Reiniciar o observador para cada experimento, para começar com contadores vazios. A execução local manual usa 5 JVMs de clientes, 5 de Sync e 1 observador; não requer Docker.

## Como a sincronização foi implementada

`Main` configura o cluster CP e inicia `ClusterSync`. Cada Sync abre um servidor socket e processa os pedidos de seu cliente sequencialmente. A concorrência ocorre entre os processos Sync, que disputam o mesmo `FencedLock("R")`.

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

Configuração por variável de ambiente:

| Variável | Padrão | Uso |
|---|---|---|
| `HZ_CLUSTER` | `tp02-sync` | nome do cluster |
| `HZ_MEMBERS` | `127.0.0.1:5701` … `:5705` | membros para o join TCP (10 processos: listar as 10 portas) |
| `HZ_CP_MEMBROS` | `5` | `cpMemberCount` do CP Subsystem (mínimo 3) |
| `HZ_BIND` | — | interface de rede (`127.0.0.1` para rodar local) |
| `OBS_HOST` | `127.0.0.1` | endereço do observador usado pelos Sync e pela consulta |
| `OBS_PORT` | `7000` | porta do observador |

## Pendências da entrega completa

- Usar os semáforos já configurados em uma etapa separada e demonstrar K=2 e K=3.
- Demonstrar reentrância na aplicação; sua configuração já está habilitada.
- Acrescentar sementes, carga desigual, repetições e consolidação de métricas; comparar 5/10 Sync quando viável.
- Produzir os slides e selecionar as evidências dos experimentos finais.

## Referência

- [Hazelcast 5.4 — FencedLock](https://docs.hazelcast.com/hazelcast/5.4/data-structures/fencedlock)
- [Hazelcast 5.4 — Configuração do CP Subsystem](https://docs.hazelcast.com/hazelcast/5.4/cp-subsystem/configuration)
- [Hazelcast 5.6 — CP Subsystem como recurso Enterprise](https://docs.hazelcast.com/hazelcast/5.6/cp-subsystem/cp-subsystem)
