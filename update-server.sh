#!/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

fail() {
    printf 'ERROR: %s\n' "$1" >&2
    exit 1
}

command -v git >/dev/null 2>&1 || fail "Falta git."
command -v docker >/dev/null 2>&1 || fail "Falta docker."
docker compose version >/dev/null 2>&1 || fail "Se necesita Docker Compose v2 (docker compose)."

cd "$PROJECT_DIR"
[ -d .git ] || fail "No se encontró un repositorio Git en $PROJECT_DIR."
[ -f compose.yaml ] || fail "No se encontró compose.yaml en $PROJECT_DIR."

printf '==> git pull --ff-only\n'
git pull --ff-only || fail "git pull falló (¿cambios locales o historial divergente?)."

printf '==> Reconstruyendo y recreando el servicio zinema\n'
docker compose up -d --build --force-recreate zinema || fail "docker compose falló."

docker compose ps zinema
printf 'Servidor actualizado.\n'
