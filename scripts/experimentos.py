#!/usr/bin/env python3
"""Executa os experimentos do TP2 e guarda configuração, logs e resumo de cada rodada.

Uso (Ubuntu/WSL, depois de `bash scripts/build.sh`):
    python3 scripts/experimentos.py                      # todos os cenários, 3 repetições onde o enunciado pede
    python3 scripts/experimentos.py --cenarios alta --repeticoes 1 --pedidos 10   # rodada curta

Cenários (todos com a seção crítica sorteada entre 200 e 1000 ms):
    normal      5 Sync, 5 clientes, pausa 1-5 s entre pedidos            (3 repetições)
    alta        5 Sync, 5 clientes, sem pausa, largada conjunta           (3 repetições)
    desigual    5 Sync; cliente 1 sem pausa e com mais pedidos, demais 1-5 s
    alta-n10    10 Sync, 10 clientes, sem pausa (comparar com "alta": mesma carga por cliente)
    sem-lock    controle negativo: alta contenção sem FencedLock (o observador deve acusar)
    semaforo2   alta contenção, mutex + etapa com semáforo K=2
    semaforo3   alta contenção, mutex + etapa com semáforo K=3

Cada rodada usa cluster novo, pasta de logs nova, observador novo e sementes registradas.
Saída: resultados/experimentos/AAAAmmdd-HHMMSS/<cenario>-r<N>/ com config.json, resumo.json,
registros/ (logs dos Sync e do observador) e saida/ (stdout de cada processo).
Depois: python3 scripts/analisar.py resultados/experimentos/AAAAmmdd-HHMMSS
"""
import argparse
import json
import os
import platform
import re
import shutil
import socket
import subprocess
import sys
import time
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / "build" / "tp02.jar"
CP = os.pathsep.join([str(JAR), str(ROOT / "lib" / "*")])
java = shutil.which("java") or "java"
# No Windows, o atalho Oracle pode deixar uma JVM filha viva ao encerrar o processo.
propriedades = subprocess.run([java, "-XshowSettings:properties", "-version"], capture_output=True, text=True, check=True)
home = re.search(r"^\s*java.home = (.+)$", propriedades.stderr, re.MULTILINE)
if home:
    java = str(Path(home.group(1).strip()) / "bin" / ("java.exe" if os.name == "nt" else "java"))
JAVA = [java, "-Xms64m", "-Xmx512m", "-XX:ActiveProcessorCount=2", "-cp", CP, "Main"]

CENARIOS = {
    #            syncs  modo         pausa cliente 1    pausa demais      repetições  pedidos cliente 1
    "normal":    dict(n=5,  modo="mutex",     rapido=None,      espera=(1000, 5000), reps=3),
    "alta":      dict(n=5,  modo="mutex",     rapido=None,      espera=(0, 0),       reps=3),
    "desigual":  dict(n=5,  modo="mutex",     rapido=(0, 0),    espera=(1000, 5000), reps=1, pedidos_rapido=50),
    "alta-n10":  dict(n=10, modo="mutex",     rapido=None,      espera=(0, 0),       reps=1),
    "sem-lock":  dict(n=5,  modo="sem-lock",  rapido=None,      espera=(0, 0),       reps=1),
    "semaforo2": dict(n=5,  modo="semaforo2", rapido=None,      espera=(0, 0),       reps=1),
    "semaforo3": dict(n=5,  modo="semaforo3", rapido=None,      espera=(0, 0),       reps=1),
}


def porta_livre(porta):
    with socket.socket() as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            s.bind(("127.0.0.1", porta))
            return True
        except OSError:
            return False


def esperar_portas(n, limite=60):
    portas = [5700 + i for i in range(1, n + 1)] + [6000 + i for i in range(1, n + 1)] + [7000]
    fim = time.monotonic() + limite
    while time.monotonic() < fim:
        ocupadas = [p for p in portas if not porta_livre(p)]
        if not ocupadas:
            return
        time.sleep(1)
    raise SystemExit(f"Portas ocupadas: {ocupadas}. Encerre os processos manuais antes dos experimentos.")


def iniciar(args, saida, env, processos):
    arquivo = open(saida, "wb")
    p = subprocess.Popen(JAVA + args, stdout=arquivo, stderr=subprocess.STDOUT, env=env, cwd=ROOT)
    processos.append((p, arquivo))
    return p


def aguardar_texto(processo, caminho, texto, limite=180):
    fim = time.monotonic() + limite
    while time.monotonic() < fim:
        if caminho.exists() and texto in caminho.read_text(errors="replace"):
            return
        if processo.poll() is not None:
            raise RuntimeError(f"{caminho.name} terminou antes de '{texto}'")
        time.sleep(0.3)
    raise RuntimeError(f"Tempo esgotado esperando '{texto}' em {caminho.name}")


def encerrar(processos):
    for p, _ in processos:
        if p.poll() is None:
            p.terminate()
    for p, arquivo in processos:
        try:
            p.wait(timeout=20)
        except subprocess.TimeoutExpired:
            p.kill()
            p.wait()
        arquivo.close()


def consultar(mensagem):
    with socket.create_connection(("127.0.0.1", 7000), timeout=5) as s:
        s.sendall((mensagem + "\n").encode())
        resposta = s.makefile("r").readline().strip()
    return {k: int(v) for k, v in re.findall(r"(\w+)=(\d+)", resposta)}


def rodada(nome, cfg, rep, pasta_base, pedidos, semente_base):
    n, modo = cfg["n"], cfg["modo"]
    k = int(modo[-1]) if modo.startswith("semaforo") else 0
    pasta = pasta_base / f"{nome}-r{rep}"
    (pasta / "saida").mkdir(parents=True)
    registros = pasta / "registros"
    semente_clientes = semente_base + 1000 * rep
    semente_sync = semente_base + 1000 * rep + 500
    membros = ",".join(f"127.0.0.1:{5700 + i}" for i in range(1, n + 1))
    env = dict(os.environ, HZ_CLUSTER="exp-" + uuid.uuid4().hex[:12], HZ_MEMBERS=membros, HZ_BIND="127.0.0.1",
               LOG_DIR=str(registros), SYNC_SEMENTE=str(semente_sync))

    pedidos_por_cliente = {}
    for c in range(1, n + 1):
        rapido = cfg["rapido"] is not None and c == 1
        pedidos_por_cliente[c] = cfg.get("pedidos_rapido", pedidos) if rapido else pedidos

    config = dict(
        cenario=nome, repeticao=rep, syncs=n, clientes=n, modo=modo, K=k or None,
        pedidos_por_cliente=pedidos_por_cliente,
        pausa_ms=dict(cliente_1=list(cfg["rapido"] or cfg["espera"]), demais=list(cfg["espera"])),
        secao_critica_ms=[200, 1000], cp_member_count=5,
        semente_clientes=semente_clientes, semente_sync=semente_sync,
        regra_sementes="cliente i usa Random(semente_clientes + i); Sync i usa Random(semente_sync + i)",
        hazelcast="5.4.0", java=subprocess.run([JAVA[0], "-version"], capture_output=True, text=True).stderr.splitlines()[0],
        sistema=platform.platform(), cpus=os.cpu_count(), cluster=env["HZ_CLUSTER"],
        inicio=time.strftime("%Y-%m-%d %H:%M:%S"),
    )
    print(f"\n== {nome} rep {rep}: {n} Sync, modo {modo}, pedidos {pedidos_por_cliente}", flush=True)

    esperar_portas(n)
    processos = []
    try:
        caminho = pasta / "saida" / "observador.out"
        aguardar_texto(iniciar(["observador"], caminho, env, processos), caminho, "OBSERVADOR_PRONTO")
        syncs = []
        for i in range(1, n + 1):
            caminho = pasta / "saida" / f"sync-{i}.out"
            args = ["sync", str(i)] + ([] if modo == "mutex" else [modo])
            syncs.append((iniciar(args, caminho, env, processos), caminho))
        for p, caminho in syncs:
            aguardar_texto(p, caminho, "SERVIDOR_PRONTO")

        # Largada conjunta: todos os clientes recebem o mesmo instante de início.
        inicio_ms = int(time.time() * 1000) + 3000
        clientes = []
        for c in range(1, n + 1):
            minimo, maximo = cfg["rapido"] if (cfg["rapido"] is not None and c == 1) else cfg["espera"]
            env_c = dict(env, CLIENTE_PEDIDOS=str(pedidos_por_cliente[c]), CLIENTE_ESPERA_MIN_MS=str(minimo),
                         CLIENTE_ESPERA_MAX_MS=str(maximo), CLIENTE_SEMENTE=str(semente_clientes),
                         CLIENTE_INICIO_MS=str(inicio_ms))
            clientes.append(iniciar(["cliente", str(c)], pasta / "saida" / f"cliente-{c}.out", env_c, processos))
        time.sleep(max(0.0, inicio_ms / 1000 - time.time()))
        t0 = time.monotonic()
        falhas = []
        for c, p in enumerate(clientes, 1):
            if p.wait(timeout=1800) != 0:
                falhas.append(c)
        tempo_total = time.monotonic() - t0

        total = sum(pedidos_por_cliente.values())
        resumo = dict(tempo_total_s=round(tempo_total, 3), pedidos_esperados=total, clientes_com_falha=falhas,
                      observador_R=consultar("SUMMARY R"))
        if k:
            resumo[f"observador_R-K{k}"] = consultar(f"SUMMARY R-K{k}")

        # Propriedades esperadas, conferidas no observador (não depende de relógio físico).
        r = resumo["observador_R"]
        ok = not falhas and r["entradas"] == r["saidas"] == total and r["ativos"] == 0 and r["erros"] == 0
        if modo == "sem-lock":
            ok = ok and r["sobreposicoes"] > 0 and r["maximo"] > 1
        else:
            ok = ok and r["sobreposicoes"] == 0 and r["maximo"] == 1
        if k:
            s = resumo[f"observador_R-K{k}"]
            ok = ok and s["entradas"] == s["saidas"] == total and s["sobreposicoes"] == 0 and 1 < s["maximo"] <= k
        # Verificador dos arquivos: exige a mesma quantidade de pedidos em todos os clientes.
        if len(set(pedidos_por_cliente.values())) == 1:
            v = subprocess.run(JAVA + ["verificar-logs", str(pedidos), str(n), str(k)], env=env, cwd=ROOT,
                               capture_output=True, text=True, timeout=60)
            (pasta / "verificacao-logs.txt").write_text(v.stdout + v.stderr)
            resumo["verificar_logs"] = "OK" if v.returncode == 0 else "FALHOU"
            esperado = 1 if modo == "sem-lock" else 0
            ok = ok and v.returncode == esperado
        resumo["propriedades_ok"] = ok
        print(json.dumps(resumo), flush=True)
    finally:
        encerrar(processos)
        config["fim"] = time.strftime("%Y-%m-%d %H:%M:%S")
        (pasta / "config.json").write_text(json.dumps(config, indent=2, ensure_ascii=False))
    (pasta / "resumo.json").write_text(json.dumps(resumo, indent=2, ensure_ascii=False))
    return ok


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--cenarios", default=",".join(CENARIOS), help="lista separada por vírgula")
    parser.add_argument("--repeticoes", type=int, help="força o número de repetições de todos os cenários")
    parser.add_argument("--pedidos", type=int, default=20, help="pedidos por cliente (10 a 50; padrão 20)")
    parser.add_argument("--semente", type=int, default=2026, help="semente base das rodadas")
    parser.add_argument("--saida", help="pasta de saída (padrão resultados/experimentos/AAAAmmdd-HHMMSS)")
    a = parser.parse_args()
    if not JAR.exists():
        raise SystemExit("Compile primeiro: bash scripts/build.sh")
    if not 10 <= a.pedidos <= 50:
        raise SystemExit("--pedidos deve estar entre 10 e 50")
    nomes = [c.strip() for c in a.cenarios.split(",") if c.strip()]
    desconhecidos = [c for c in nomes if c not in CENARIOS]
    if desconhecidos:
        raise SystemExit(f"Cenários desconhecidos: {desconhecidos}. Opções: {', '.join(CENARIOS)}")
    pasta = Path(a.saida) if a.saida else ROOT / "resultados" / "experimentos" / time.strftime("%Y%m%d-%H%M%S")
    pasta.mkdir(parents=True, exist_ok=False)
    resultados = {}
    for nome in nomes:
        cfg = CENARIOS[nome]
        for rep in range(1, (a.repeticoes or cfg["reps"]) + 1):
            resultados[f"{nome}-r{rep}"] = rodada(nome, cfg, rep, pasta, a.pedidos, a.semente)
    (pasta / "rodadas.json").write_text(json.dumps(resultados, indent=2))
    print("\nRodadas:", json.dumps(resultados))
    print("Saída:", pasta)
    if all(resultados.values()):
        print("EXPERIMENTOS_OK")
    else:
        print("EXPERIMENTOS_COM_FALHA")
        sys.exit(1)


if __name__ == "__main__":
    main()
