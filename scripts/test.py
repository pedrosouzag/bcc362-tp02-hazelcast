#!/usr/bin/env python3
"""Teste local do fluxo completo. Requer Python 3, Java e o projeto compilado."""
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
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
        return summary
    finally:
        stop(processes)


def experiment(protected, base_env):
    name = "com-lock" if protected else "sem-lock"
    folder = RUN / name
    folder.mkdir()
    processes = []
    env = dict(base_env, HZ_CLUSTER="teste-" + uuid.uuid4().hex)
    try:
        path = folder / "observador.log"
        ready(start(["observador"], path, env, processes), path, "OBSERVADOR_PRONTO")
        members = []
        for id in range(1, 6):
            path = folder / ("sync-" + str(id) + ".log")
            args = ["sync", str(id)] + ([] if protected else ["sem-lock"])
            members.append((start(args, path, env, processes), path))
        for process, path in members:
            ready(process, path, "SERVIDOR_PRONTO")
        # Os cinco clientes são iniciados juntos, sem pausa entre seus pedidos.
        clients = [start(["cliente", str(id), "10", "0"], folder / ("cliente-" + str(id) + ".log"), env, processes)
                   for id in range(1, 6)]
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
                events = [line.split()[0] for line in lines if key in line]
                check(events == ["REQUEST", "ACQUIRE", "ENTER", "EXIT", "RELEASE", "COMMITTED"], "Ordem de eventos incorreta: " + key)
        print(name + ": " + json.dumps(summary), flush=True)
        return summary
    finally:
        stop(processes)


if __name__ == "__main__":
    free_ports()
    env = dict(os.environ, HZ_BIND="127.0.0.1", HZ_CP_MEMBROS="5", OBS_HOST="127.0.0.1", OBS_PORT="7000",
               HZ_MEMBERS=",".join("127.0.0.1:" + str(port) for port in range(5701, 5706)))
    results = {"cliente": test_client(), "observador": test_observer(env)}
    print("Validação do cliente e do observador: OK", flush=True)
    results["sem-lock"] = experiment(False, env)
    results["com-lock"] = experiment(True, env)
    results["escopo"] = "Teste funcional inicial; não substitui as repetições e métricas da entrega final."
    (RUN / "resumo.json").write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print("TESTES_OK logs=" + str(RUN), flush=True)
