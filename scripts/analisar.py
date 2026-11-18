#!/usr/bin/env python3
"""Consolida as métricas de uma pasta gerada por scripts/experimentos.py.

Uso:
    python3 scripts/analisar.py resultados/experimentos/AAAAmmdd-HHMMSS

Gera na mesma pasta:
    RESUMO.md      tabelas por rodada, por cenário (mediana das repetições) e por cliente
    metricas.csv   uma linha por rodada
    clientes.csv   uma linha por (rodada, cliente)

Métricas (todas medidas no próprio processo, com relógio monotônico, sem subtrair horários de hosts):
    concluídos       eventos COMMITTED nos logs dos Sync
    tempo total      da largada conjunta até o último cliente terminar (relógio do script)
    espera           espera_ns do ACQUIRE: do pedido de acquire até obter o lock/permissão, no Sync
    resposta         resposta_ms do cliente: envio do WRITE até receber COMMITTED
    acessos/cliente  COMMITTED por cliente
    mensagens        aplicação e observador contadas pelo protocolo da aplicação;
                     protocolo (Raft) estimado em 4G por etapa (ver docs/MENSAGENS.md);
                     entradas Raft observadas ≈ avanço do fence entre acessos consecutivos.
"""
import csv
import json
import re
import statistics
import sys
from pathlib import Path

G = 5  # membros do grupo CP (cpMemberCount)
ORDEM = ["normal", "alta", "desigual", "alta-n10", "sem-lock", "semaforo2", "semaforo3"]


def mediana(valores):
    return round(statistics.median(valores), 1) if valores else None


def percentil(valores, p):
    if not valores:
        return None
    v = sorted(valores)
    return round(v[min(len(v) - 1, int(round(p / 100 * (len(v) - 1))))], 1)


def ler_sync(pasta):
    eventos = []
    for arquivo in sorted((pasta / "registros").glob("sync-*.log")):
        for linha in arquivo.read_text(errors="replace").splitlines():
            partes = linha.split(";")
            if len(partes) < 8:
                continue
            _, sync, pedido, evento, fence, recurso, _, detalhe = partes[:8]
            info = dict(re.findall(r"(\w+)=(-?\d+)", detalhe))
            eventos.append(dict(sync=int(sync), cliente=int(pedido.split("-")[0]), pedido=pedido, evento=evento,
                                fence=int(fence), recurso=recurso, **{k: int(v) for k, v in info.items()}))
    return eventos


def ler_respostas(pasta):
    respostas = {}
    for arquivo in (pasta / "saida").glob("cliente-*.out"):
        c = int(arquivo.stem.split("-")[1])
        respostas[c] = [int(m) for m in re.findall(r"resposta_ms=(\d+)", arquivo.read_text(errors="replace"))]
    return respostas


def analisar_rodada(pasta):
    config = json.loads((pasta / "config.json").read_text())
    resumo = json.loads((pasta / "resumo.json").read_text()) if (pasta / "resumo.json").exists() else {}
    eventos = ler_sync(pasta)
    respostas = ler_respostas(pasta)
    k = config.get("K")
    protegido = config["modo"] != "sem-lock"

    def esperas(recurso, cliente=None):
        return [e["espera_ns"] / 1e6 for e in eventos if e["evento"] == "ACQUIRE" and e["recurso"] == recurso
                and "espera_ns" in e and (cliente is None or e["cliente"] == cliente)]

    concluidos = [e for e in eventos if e["evento"] == "COMMITTED"]
    duracoes = [e["duracao_ms"] for e in eventos if e["evento"] == "ENTER" and e["recurso"] == "R"]
    tempo = resumo.get("tempo_total_s")
    etapas = 2 if k else 1
    fences = sorted(e["fence"] for e in eventos if e["evento"] == "ACQUIRE" and e["recurso"] == "R")
    raft_por_acesso = round((fences[-1] - fences[0]) / (len(fences) - 1), 2) if protegido and len(fences) > 1 else None
    obs = resumo.get("observador_R", {})
    sem = resumo.get(f"observador_R-K{k}", {}) if k else {}
    todas_respostas = [r for lista in respostas.values() for r in lista]

    linha = dict(
        rodada=pasta.name, cenario=config["cenario"], repeticao=config["repeticao"], syncs=config["syncs"],
        modo=config["modo"], esperados=sum(int(v) for v in config["pedidos_por_cliente"].values()),
        concluidos=len(concluidos), tempo_total_s=tempo,
        vazao_por_s=round(len(concluidos) / tempo, 3) if tempo else None,
        espera_mediana_ms=mediana(esperas("R")), espera_p90_ms=percentil(esperas("R"), 90),
        espera_max_ms=round(max(esperas("R")), 1) if esperas("R") else None,
        espera_semaforo_mediana_ms=mediana(esperas(f"R-K{k}")) if k else None,
        resposta_mediana_ms=mediana(todas_respostas), secao_critica_media_ms=round(statistics.mean(duracoes), 1) if duracoes else None,
        ocupacao_max_R=obs.get("maximo"), sobreposicoes_R=obs.get("sobreposicoes"),
        ocupacao_max_semaforo=sem.get("maximo"),
        msg_aplicacao_por_pedido=2, msg_observador_por_pedido=4 * etapas,
        msg_protocolo_estimada_por_pedido=(4 * G * etapas if protegido else 4 * G * (etapas - 1)),
        entradas_raft_por_acesso_obs=raft_por_acesso,
        semente_clientes=config["semente_clientes"], semente_sync=config["semente_sync"],
        propriedades_ok=resumo.get("propriedades_ok"),
    )
    clientes = []
    for c in sorted(int(x) for x in config["pedidos_por_cliente"]):
        clientes.append(dict(
            rodada=pasta.name, cenario=config["cenario"], cliente=c,
            pedidos=config["pedidos_por_cliente"][str(c)], acessos=sum(1 for e in concluidos if e["cliente"] == c),
            espera_mediana_ms=mediana(esperas("R", c)), espera_max_ms=round(max(esperas("R", c)), 1) if esperas("R", c) else None,
            resposta_mediana_ms=mediana(respostas.get(c, [])),
        ))
    return linha, clientes


def tabela(linhas, colunas):
    out = ["| " + " | ".join(colunas) + " |", "|" + "---|" * len(colunas)]
    for l in linhas:
        out.append("| " + " | ".join("—" if l.get(c) is None else str(l.get(c)) for c in colunas) + " |")
    return "\n".join(out)


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    base = Path(sys.argv[1])
    rodadas = sorted(p for p in base.iterdir() if (p / "config.json").exists())
    if not rodadas:
        raise SystemExit(f"Nenhuma rodada em {base}")
    linhas, clientes = [], []
    for pasta in rodadas:
        l, c = analisar_rodada(pasta)
        linhas.append(l)
        clientes.extend(c)
    # Mesma ordem dos cenários em experimentos.py (e não alfabética).
    posicao = lambda nome: ORDEM.index(nome) if nome in ORDEM else len(ORDEM)
    linhas.sort(key=lambda l: (posicao(l["cenario"]), l["repeticao"]))
    clientes.sort(key=lambda c: (posicao(c["cenario"]), c["rodada"], c["cliente"]))

    with open(base / "metricas.csv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(linhas[0]))
        w.writeheader()
        w.writerows(linhas)
    with open(base / "clientes.csv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(clientes[0]))
        w.writeheader()
        w.writerows(clientes)

    # Por cenário: mediana das repetições (só faz sentido entre rodadas com a mesma configuração).
    cenarios = []
    for nome in dict.fromkeys(l["cenario"] for l in linhas):
        grupo = [l for l in linhas if l["cenario"] == nome]
        agg = dict(cenario=nome, repeticoes=len(grupo), syncs=grupo[0]["syncs"],
                   concluidos=f"{sum(l['concluidos'] for l in grupo)}/{sum(l['esperados'] for l in grupo)}",
                   propriedades_ok=all(l["propriedades_ok"] for l in grupo))
        for campo in ("tempo_total_s", "vazao_por_s", "espera_mediana_ms", "espera_p90_ms", "resposta_mediana_ms",
                      "ocupacao_max_R", "ocupacao_max_semaforo", "entradas_raft_por_acesso_obs"):
            valores = [l[campo] for l in grupo if l[campo] is not None]
            agg[campo] = None if not valores else (valores[0] if len(valores) == 1 else
                                                   f"{mediana(valores)} ({min(valores)}–{max(valores)})")
        cenarios.append(agg)

    md = [f"# Resumo dos experimentos — {base.name}", "",
          "Gerado por `scripts/analisar.py`. Espera medida no Sync com `System.nanoTime()`; tempo total medido",
          "pelo script, da largada conjunta ao fim do último cliente. Seção crítica sorteada em 200–1000 ms.",
          "**Não comparar diretamente cenários com cargas ou durações diferentes**; com várias repetições,",
          "o valor é a mediana (mínimo–máximo).", "",
          "## Por cenário", "",
          tabela(cenarios, ["cenario", "repeticoes", "syncs", "concluidos", "tempo_total_s", "vazao_por_s",
                            "espera_mediana_ms", "espera_p90_ms", "resposta_mediana_ms", "ocupacao_max_R",
                            "ocupacao_max_semaforo", "entradas_raft_por_acesso_obs", "propriedades_ok"]), "",
          "## Por rodada", "",
          tabela(linhas, ["rodada", "concluidos", "esperados", "tempo_total_s", "espera_mediana_ms", "espera_max_ms",
                          "espera_semaforo_mediana_ms", "secao_critica_media_ms", "ocupacao_max_R", "sobreposicoes_R",
                          "semente_clientes", "semente_sync", "propriedades_ok"]), "",
          "## Mensagens por pedido", "",
          "Aplicação (WRITE/COMMITTED) e observador (BEGIN/OK e END/OK por etapa) são contadas pelo protocolo da",
          f"aplicação. Protocolo Raft estimado em 4G = {4 * G} por etapa protegida (G = {G} membros CP); não é",
          "exposto pela API. `entradas_raft_por_acesso_obs` = avanço médio do fence entre acessos consecutivos",
          "(o fence é o commitIndex Raft; inclui heartbeats de sessão e unlocks).", "",
          tabela(linhas, ["rodada", "msg_aplicacao_por_pedido", "msg_observador_por_pedido",
                          "msg_protocolo_estimada_por_pedido", "entradas_raft_por_acesso_obs"]), "",
          "## Por cliente (acessos e espera)", "",
          tabela(clientes, ["rodada", "cliente", "pedidos", "acessos", "espera_mediana_ms", "espera_max_ms",
                            "resposta_mediana_ms"]), ""]
    (base / "RESUMO.md").write_text("\n".join(md), encoding="utf-8")
    print("\n".join(md[:12]))
    print(f"\nGerado: {base / 'RESUMO.md'}, metricas.csv, clientes.csv")


if __name__ == "__main__":
    main()
