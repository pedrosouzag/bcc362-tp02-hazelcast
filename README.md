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
bash scripts/run.sh <argumentos>
```

> Os argumentos de execução serão documentados aqui conforme a implementação do TP02 avançar.

## Referência

- [Hazelcast 5.4 — FencedLock](https://docs.hazelcast.com/hazelcast/5.4/data-structures/fencedlock)
- [Hazelcast 5.4 — Configuração do CP Subsystem](https://docs.hazelcast.com/hazelcast/5.4/cp-subsystem/configuration)
- [Hazelcast 5.6 — CP Subsystem como recurso Enterprise](https://docs.hazelcast.com/hazelcast/5.6/cp-subsystem/cp-subsystem)
