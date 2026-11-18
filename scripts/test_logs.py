#!/usr/bin/env python3
"""Testa o verificador com cópias alteradas de uma execução válida de mutex."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
base = Path(sys.argv[1]).resolve()
dest = base.parent / "regressoes-logs"
dest.mkdir(exist_ok=True)
java = shutil.which("java")
props = subprocess.run([java, "-XshowSettings:properties", "-version"], capture_output=True, text=True)
home = re.search(r"^\s*java.home = (.+)$", props.stderr, re.MULTILINE)
if home:
    java = str(Path(home.group(1).strip()) / "bin" / ("java.exe" if os.name == "nt" else "java"))
command = [java, "-cp", str(root / "build/tp02.jar") + os.pathsep + str(root / "lib/*"),
           "Main", "verificar-logs", "10", "5"]
cases = ["valido", "vazio", "participante_ausente", "pedido_trocado", "saida_duplicada",
         "tempo_invertido", "fence_trocado", "evento_invalido", "sem_confirmacao",
         "observador_vazio", "observador_sobreposicao"]
results = []
for case in cases:
    folder = dest / case
    shutil.copytree(base, folder, dirs_exist_ok=True)
    file = folder / "sync-1.log"
    lines = file.read_text(encoding="utf-8").splitlines()
    exit_index = next(i for i, line in enumerate(lines) if ";EXIT;" in line)
    if case == "vazio":
        for path in folder.glob("*.log"):
            path.write_text("")
    elif case == "participante_ausente":
        (folder / "sync-5.log").unlink()
    elif case == "saida_duplicada":
        lines.insert(exit_index, lines[exit_index])
    elif case in ("pedido_trocado", "tempo_invertido", "fence_trocado", "evento_invalido"):
        parts = lines[exit_index].split(";")
        if case == "pedido_trocado":
            parts[2] = "2-99"
        elif case == "tempo_invertido":
            parts[6] = str(int(lines[0].split(";")[6]) - 1)
        elif case == "fence_trocado":
            parts[4] = str(int(parts[4]) + 1)
        else:
            parts[3] = "DESCONHECIDO"
        lines[exit_index] = ";".join(parts)
    elif case == "sem_confirmacao":
        lines = [line for line in lines if ";COMMITTED;" not in line]
    elif case == "observador_vazio":
        (folder / "observador.log").write_text("")
    elif case == "observador_sobreposicao":
        obs = (folder / "observador.log").read_text().splitlines()
        # Aproxima o segundo BEGIN do primeiro, criando sobreposição sem mudar IDs.
        second_begin = next(i for i in range(1, len(obs)) if obs[i].startswith("BEGIN"))
        obs.insert(1, obs.pop(second_begin))
        (folder / "observador.log").write_text("\n".join(obs) + "\n")
    if case != "vazio":
        file.write_text("\n".join(lines) + "\n", encoding="utf-8")
    run = subprocess.run(command, env=dict(os.environ, LOG_DIR=str(folder)), capture_output=True, timeout=15)
    (dest / (case + ".txt")).write_bytes(run.stdout + run.stderr)
    expected = 0 if case == "valido" else 1
    if run.returncode != expected:
        raise AssertionError(case + ": resultado inesperado")
    results.append({"caso": case, "status": run.returncode, "esperado": expected})
(dest / "resumo.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
print("REGRESSOES_OK: 1 caso válido e 10 inválidos verificados")
