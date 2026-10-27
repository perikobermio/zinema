#!/usr/bin/env python3
"""Runs the fixed, admin-requested NAS pull and Zinema container recreation."""

from __future__ import annotations

import json
import os
import queue
import subprocess
import threading
import time
from pathlib import Path

PROJECT_ROOT = Path(os.environ.get("ZINEMA_PROJECT_ROOT", "/var/www/zinema")).resolve()
STATE_PATH = Path(os.environ.get("ZINEMA_UPDATE_STATE", "/data/server-update.json"))
POLL_SECONDS = 1.0
MAX_OUTPUT = 24_000


def read_state() -> dict | None:
    try:
        value = json.loads(STATE_PATH.read_text(encoding="utf-8"))
        return value if isinstance(value, dict) else None
    except (OSError, json.JSONDecodeError):
        return None


def write_state(state: dict) -> None:
    STATE_PATH.parent.mkdir(parents=True, exist_ok=True)
    temporary = STATE_PATH.with_suffix(".tmp")
    temporary.write_text(json.dumps(state, ensure_ascii=False), encoding="utf-8")
    os.replace(temporary, STATE_PATH)


def append_output(state: dict, text: str) -> None:
    existing = str(state.get("output") or "")
    state["output"] = (existing + text)[-MAX_OUTPUT:]
    write_state(state)


def run_step(state: dict, title: str, command: list[str], timeout: int) -> None:
    state.update(status="running", stage=title)
    write_state(state)
    lines: queue.Queue[str | None] = queue.Queue()
    try:
        process = subprocess.Popen(
            command,
            cwd=PROJECT_ROOT,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            errors="replace",
            bufsize=1,
        )
    except OSError as error:
        raise RuntimeError(f"{title}: no se pudo ejecutar {command[0]}: {error}") from error

    def collect_output() -> None:
        assert process.stdout is not None
        for line in process.stdout:
            lines.put(line)
        lines.put(None)

    reader = threading.Thread(target=collect_output, daemon=True)
    reader.start()
    started = time.monotonic()
    last_heartbeat = started
    reader_finished = False
    while process.poll() is None or not reader_finished:
        try:
            line = lines.get(timeout=1.0)
            if line is None:
                reader_finished = True
            else:
                append_output(state, line)
        except queue.Empty:
            pass
        now = time.monotonic()
        if now - started > timeout:
            process.kill()
            process.wait()
            reader.join(timeout=2.0)
            raise RuntimeError(f"{title}: tiempo de espera agotado ({timeout} s)")
        if now - last_heartbeat >= 5.0:
            state["heartbeat_at"] = int(time.time())
            write_state(state)
            last_heartbeat = now
    return_code = process.wait()
    if return_code:
        raise RuntimeError(f"{title}: el comando terminó con código {return_code}")


def process(state: dict) -> None:
    job_id = state.get("id")
    state.update(status="running", stage="Preparando actualización", output="",
                 heartbeat_at=int(time.time()))
    write_state(state)
    try:
        if not (PROJECT_ROOT / ".git").is_dir():
            raise RuntimeError(f"No se encontró un repositorio Git en {PROJECT_ROOT}")
        if not (PROJECT_ROOT / "compose.yaml").is_file():
            raise RuntimeError(f"No se encontró compose.yaml en {PROJECT_ROOT}")
        run_step(state, "Descargando cambios (git pull)",
                 ["git", "pull", "--ff-only"], 600)
        run_step(state, "Reconstruyendo y recreando el servidor",
                 ["docker", "compose", "-f", str(PROJECT_ROOT / "compose.yaml"),
                  "--project-directory", str(PROJECT_ROOT), "up", "-d", "--build",
                  "--force-recreate", "zinema"], 1800)
        state.update(status="completed", stage="Actualización completada", error=None)
    except Exception as error:  # Preserve the reason and command output for the admin.
        state.update(status="failed", stage="Actualización fallida", error=str(error))
    state["id"] = job_id
    state["finished_at"] = int(time.time())
    write_state(state)


def main() -> None:
    while True:
        state = read_state()
        if state and state.get("status") == "queued" and state.get("id"):
            process(state)
        time.sleep(POLL_SECONDS)


if __name__ == "__main__":
    main()
