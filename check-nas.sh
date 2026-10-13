#!/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ENV_FILE="$PROJECT_DIR/.env"

fail() {
    printf 'ERROR: %s\n' "$1" >&2
    exit 1
}

[ -f "$ENV_FILE" ] || fail "Falta $ENV_FILE. Cópialo desde .env.example y configúralo."
command -v docker >/dev/null 2>&1 || fail "Docker no está instalado o no está en PATH."
command -v curl >/dev/null 2>&1 || fail "Falta curl."
docker info >/dev/null 2>&1 || fail "Docker no está activo o el usuario no tiene permisos para usarlo."
docker compose version >/dev/null 2>&1 || fail "Se necesita Docker Compose v2 (docker compose)."

# .env debe contener asignaciones simples KEY=value, como .env.example.
set -a
. "$ENV_FILE"
set +a

[ -n "${ADMIN_TOKEN:-}" ] || fail "ADMIN_TOKEN está vacío en .env."
[ "$ADMIN_TOKEN" != "replace-this-with-a-long-random-secret" ] || \
    fail "Cambia ADMIN_TOKEN en .env por un secreto propio."
[ -n "${MOVIES_PATH:-}" ] || fail "Falta MOVIES_PATH en .env."
[ -n "${SERIES_PATH:-}" ] || fail "Falta SERIES_PATH en .env."
[ -d "$MOVIES_PATH" ] || fail "No existe la carpeta de películas: $MOVIES_PATH"
[ -r "$MOVIES_PATH" ] || fail "Docker/este usuario no puede leer: $MOVIES_PATH"
[ -d "$SERIES_PATH" ] || fail "No existe la carpeta de series: $SERIES_PATH"
[ -r "$SERIES_PATH" ] || fail "Docker/este usuario no puede leer: $SERIES_PATH"

cd "$PROJECT_DIR"
docker compose config -q || fail "La configuración de Docker Compose no es válida."

printf 'OK: Docker está activo y Compose v2 está disponible.\n'
printf 'OK: .env contiene token y las dos rutas existen y se pueden leer.\n'
printf 'OK: la configuración de Docker Compose es válida.\n'
