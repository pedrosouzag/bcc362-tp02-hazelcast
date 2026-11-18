#!/usr/bin/env bash
# Imprime a lista de membros do Cluster Sync para o join TCP (HZ_MEMBERS).
# Uso:  export HZ_MEMBERS=$(bash scripts/membros.sh 10)
# Gera 127.0.0.1:5701,...,127.0.0.1:57NN. Padrão: 5 membros.
# O cpMemberCount continua 5 (HZ_CP_MEMBROS): com 10 syncs, 5 formam o grupo Raft
# e os outros 5 usam o FencedLock como membros comuns (AP).
set -euo pipefail
n=${1:-5}
host=${2:-127.0.0.1}
lista=""
for i in $(seq 1 "$n"); do
  lista+="${lista:+,}$host:$((5700 + i))"
done
echo "$lista"
