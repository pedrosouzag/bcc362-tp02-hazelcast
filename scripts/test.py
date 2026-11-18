#!/usr/bin/env python3
"""Teste local do fluxo completo. Requer Python 3, Java e o projeto compilado."""
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
RUN = ROOT / "build" / ("teste-" + time.strftime("%Y%m%d-%H%M%S"))
RUN.mkdir(parents=True, exist_ok=True)
java = shutil.which("java")
if not java:
    raise SystemExit("Java não encontrado.")
# No Windows, o atalho Oracle inicia outro processo. Usamos o JDK diretamente.
properties = subprocess.run([java, "-XshowSettings:properties", "-version"], capture_output=True, text=True)
home = re.search(r"^\s*java.home = (.+)$", properties.stderr, re.MULTILINE)
if home:
    java = str(Path(home.group(1).strip()) / "bin" / ("java.exe" if os.name == "nt" else "java"))
CP = os.pathsep.join([str(ROOT / "build/tp02.jar"), str(ROOT / "lib/*")])
COMMAND = [java, "-Xms64m", "-Xmx512m", "-XX:ActiveProcessorCount=2", "-cp", CP, "Main"]
if not (ROOT / "build/tp02.jar").exists():
    raise SystemExit("Compile primeiro: bash scripts/build.sh")


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def free_ports():
    for port in [*range(5701, 5706), *range(6001, 6006), 7000]:
        with socket.socket() as sock:
            # Igual ao ServerSocket do Java no Linux: TIME_WAIT de uma execução anterior não conta como ocupada.
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind(("127.0.0.1", port))


def query(message):
    with socket.create_connection(("127.0.0.1", 7000), timeout=5) as sock:
        sock.sendall((message + "\n").encode())
        return sock.makefile("r").readline().strip()


def fields(summary):
    return {key: int(value) for key, value in re.findall(r"(\w+)=(\d+)", summary)}


def start(args, path, env, processes):
    with path.open("w", encoding="utf-8") as output:
        process = subprocess.Popen(COMMAND + args, cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT)
    processes.append(process)
    return process


def ready(process, path, marker):
    limit = time.monotonic() + 120
    while time.monotonic() < limit:
        check(process.poll() is None, "Processo encerrou: " + str(path))
        if marker in path.read_text(encoding="utf-8", errors="replace"):
            return
        time.sleep(0.2)
    raise AssertionError("Inicialização excedeu 120 s: " + str(path))


def stop(processes):
    for process in reversed(processes):
        if process.poll() is None:
            process.terminate()
    for process in processes:
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)


def test_client():
    results = []
    for name, reply in [("id_incorreto", b"COMMITTED 9-99\n"), ("erro", b"ERROR rejeitado\n"), ("eof", b"")]:
        received = []
        with socket.socket() as server:
            # No Linux/WSL a porta fica em TIME_WAIT entre os casos; sem isto o segundo bind falha.
            server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            server.bind(("127.0.0.1", 6001))
            server.listen()
            server.settimeout(10)

            def respond():
                with server.accept()[0] as sock:
                    received.append(sock.makefile("r").readline().strip())
                    if reply:
                        sock.sendall(reply)

            thread = threading.Thread(target=respond, daemon=True)
            thread.start()
            result = subprocess.run(COMMAND + ["cliente", "1", "10", "0"], capture_output=True, timeout=15)
            thread.join(timeout=10)
        (RUN / ("cliente-" + name + ".log")).write_bytes(result.stdout + result.stderr)
        check(received == ["WRITE 1-1"] and result.returncode != 0, "Cliente aceitou " + name)
        check(b"CLIENTE_CONCLUIDO" not in result.stdout, "Cliente concluiu com resposta inválida")
        results.append(name + ": rejeitado")
    return results


def test_observer(env):
    env = dict(env, LOG_DIR=str(RUN / "registros-protocolo"))
    processes = []
    path = RUN / "observador-protocolo.log"
    try:
        ready(start(["observador"], path, env, processes), path, "OBSERVADOR_PRONTO")
        check(query("BEGIN 1 1-1") == "OK", "BEGIN falhou")
        check(query("BEGIN 2 2-1") == "OK", "Observador bloqueou sobreposição")
        check(query("BEGIN 1 1-1").startswith("ERROR"), "Duplicata aceita")
        check(query("END 3 3-1").startswith("ERROR"), "END sem BEGIN aceito")
        check(query("END 1 1-1") == "OK" and query("END 2 2-1") == "OK", "END falhou")
        summary = fields(query("SUMMARY"))
        check(summary == dict(entradas=2, saidas=2, ativos=0, maximo=2, sobreposicoes=1, erros=2), "Contagem incorreta")
        check(query("BEGIN 1 1-1").startswith("ERROR"), "Repetição após END foi aceita")
        for k in (2, 3):
            for id in range(1, k + 2):
                check(query(f"BEGIN {id} {id}-1 R-K{k}") == "OK", "Observador bloqueou entrada")
            value = fields(query(f"SUMMARY R-K{k}"))
            check(value["maximo"] == k + 1 and value["sobreposicoes"] == 1, "Limite K não verificado")
            for id in range(1, k + 2):
                check(query(f"END {id} {id}-1 R-K{k}") == "OK", "END do semáforo falhou")
        return summary
    finally:
        stop(processes)


def experiment(protected, base_env, k=0):
    name = "semaforo" + str(k) if k else ("com-lock" if protected else "sem-lock")
    folder = RUN / name
    folder.mkdir()
    processes = []
    env = dict(base_env, HZ_CLUSTER="teste-" + uuid.uuid4().hex, LOG_DIR=str(folder / "registros"))
    try:
        path = folder / "observador.log"
        ready(start(["observador"], path, env, processes), path, "OBSERVADOR_PRONTO")
        members = []
        for id in range(1, 6):
            path = folder / ("sync-" + str(id) + ".log")
            args = ["sync", str(id)] + (["semaforo" + str(k)] if k else ([] if protected else ["sem-lock"]))
            members.append((start(args, path, env, processes), path))
        for process, path in members:
            ready(process, path, "SERVIDOR_PRONTO")
        for invalid in ("DELETE 1-1", "WRITE 2-1", "WRITE", "WRITE 1-0"):
            with socket.create_connection(("127.0.0.1", 6001), timeout=5) as sock:
                sock.sendall((invalid + "\n").encode())
                check(sock.makefile("r").readline().startswith("ERROR"), "Pedido inválido aceito")
        # Os cinco clientes são iniciados juntos, sem pausa entre seus pedidos.
        clients = []
        for id in range(1, 6):
            # Pares usam ambiente; ímpares provam que argumentos têm prioridade.
            client_env = dict(env, CLIENTE_PEDIDOS="10" if id % 2 == 0 else "20",
                CLIENTE_ESPERA_MIN_MS="0" if id % 2 == 0 else "1000",
                CLIENTE_ESPERA_MAX_MS="0" if id % 2 == 0 else "2000", CLIENTE_SEMENTE="42")
            args = ["cliente", str(id)] + ([] if id % 2 == 0 else ["10", "0"])
            clients.append(start(args, folder / ("cliente-" + str(id) + ".log"), client_env, processes))
        for client in clients:
            check(client.wait(timeout=120) == 0, "Cliente falhou em " + name)
        summary = fields(query("SUMMARY"))
        check(summary["entradas"] == summary["saidas"] == 50, "Pedidos incompletos")
        check(summary["ativos"] == summary["erros"] == 0, "Observador terminou inconsistente")
        check(summary["maximo"] == 1 if protected else summary["maximo"] > 1, "Ocupação inesperada")
        check(summary["sobreposicoes"] == 0 if protected else summary["sobreposicoes"] > 0, "Controle inconclusivo")
        for id in range(1, 6):
            client_log = (folder / ("cliente-" + str(id) + ".log")).read_text(errors="replace")
            check(client_log.count("COMMITTED cliente=") == 10, "Confirmações incompletas")
            lines = (folder / ("sync-" + str(id) + ".log")).read_text(errors="replace").splitlines()
            for seq in range(1, 11):
                key = "request_id=" + str(id) + "-" + str(seq) + " "
                events = [line.split()[0] for line in lines if key in line and "recurso=R " in line]
                check(events == ["REQUEST", "ACQUIRE", "ENTER", "EXIT", "RELEASE", "COMMITTED"], "Ordem de eventos incorreta: " + key)
        if k:
            sem = fields(query("SUMMARY R-K" + str(k)))
            check(sem["entradas"] == sem["saidas"] == 50 and sem["ativos"] == sem["erros"] == 0, "Semáforo incompleto")
            check(1 < sem["maximo"] <= k and sem["sobreposicoes"] == 0, "Capacidade ou concorrência incorreta")
            summary = {"mutex": summary, "semaforo": sem}
        offline = subprocess.run(COMMAND + ["verificar-logs", "10", "5", str(k)], env=env, cwd=ROOT, capture_output=True, timeout=15)
        (folder / "verificacao-logs.log").write_bytes(offline.stdout + offline.stderr)
        check(offline.returncode == (0 if protected else 1), "Verificação offline incorreta")
        if k == 3:
            probe = subprocess.run([java, "-XX:ActiveProcessorCount=2", "-cp", str(RUN / "classes") + os.pathsep + CP,
                                    "PrimitivasTest"], env=env, cwd=ROOT, capture_output=True, timeout=60)
            (folder / "primitivas.log").write_bytes(probe.stdout + probe.stderr)
            check(probe.returncode == 0 and b"PROBE_OK" in probe.stdout, "Teste das primitivas falhou")
        print(name + ": " + json.dumps(summary), flush=True)
        return summary
    finally:
        stop(processes)


if __name__ == "__main__":
    free_ports()
    (RUN / "classes").mkdir()
    javac = str(Path(java).with_name("javac.exe" if os.name == "nt" else "javac"))
    subprocess.run([javac, "--release", "17", "-encoding", "UTF-8", "-cp", CP, "-d", str(RUN / "classes"),
                    str(ROOT / "tests/ClienteConfigTest.java")], check=True)
    subprocess.run([java, "-cp", str(RUN / "classes") + os.pathsep + CP, "ClienteConfigTest"], check=True)
    env = dict(os.environ, HZ_BIND="127.0.0.1", HZ_CP_MEMBROS="5", OBS_HOST="127.0.0.1", OBS_PORT="7000",
               HZ_MEMBERS=",".join("127.0.0.1:" + str(port) for port in range(5701, 5706)))
    results = {"cliente": test_client(), "observador": test_observer(env)}
    print("Validação do cliente e do observador: OK", flush=True)
    results["sem-lock"] = experiment(False, env)
    results["com-lock"] = experiment(True, env)
    subprocess.run([sys.executable, str(ROOT / "scripts/test_logs.py"),
                    str(RUN / "com-lock/registros")], check=True)
    (RUN / "classes").mkdir(exist_ok=True)
    javac = str(Path(java).with_name("javac.exe" if os.name == "nt" else "javac"))
    subprocess.run([javac, "--release", "17", "-encoding", "UTF-8", "-cp", CP, "-d", str(RUN / "classes"),
                    str(ROOT / "tests/PrimitivasTest.java")], check=True)
    results["semaforo2"] = experiment(True, env, 2)
    results["semaforo3"] = experiment(True, env, 3)
    results["escopo"] = "Teste funcional inicial; não substitui as repetições e métricas da entrega final."
    (RUN / "resumo.json").write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print("TESTES_OK logs=" + str(RUN), flush=True)
