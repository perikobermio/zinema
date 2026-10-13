#!/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
API_BASE_URL=${API_BASE_URL:-http://127.0.0.1:8180}
API_BASE_URL=${API_BASE_URL%/}

fail() {
    printf 'ERROR: %s\n' "$1" >&2
    exit 1
}

command -v curl >/dev/null 2>&1 || fail "Falta curl."
[ -f "$PROJECT_DIR/.env" ] || fail "No existe $PROJECT_DIR/.env. Cópialo desde .env.example y configúralo."

set -a
. "$PROJECT_DIR/.env"
set +a
[ -n "${ADMIN_TOKEN:-}" ] || fail "ADMIN_TOKEN está vacío en .env."

printf 'Iniciando la sincronización del catálogo en %s...\n' "$API_BASE_URL"
response=$(printf 'Authorization: Bearer %s\n' "$ADMIN_TOKEN" | \
    curl --silent --show-error --fail-with-body --max-time 30 \
    --request POST --header @- "$API_BASE_URL/api/v1/admin/sync") || \
    fail "No se pudo iniciar la sincronización. Comprueba que el servidor esté activo y TMDB_KEY esté configurada."
unset ADMIN_TOKEN

job_id=$(printf '%s\n' "$response" | sed -n 's/.*"id":"\([a-fA-F0-9][a-fA-F0-9]*\)".*/\1/p')
[ -n "$job_id" ] || fail "El servidor respondió, pero no se encontró el ID del trabajo: $response"

printf 'Importación iniciada. ID: %s\n' "$job_id"
printf 'Consulta el progreso con:\n'
printf '  set -a && . %s/.env && set +a\n' "$PROJECT_DIR"
printf '  curl "%s/api/v1/admin/sync/%s" -H "Authorization: Bearer \$ADMIN_TOKEN"\n' \
    "$API_BASE_URL" "$job_id"
