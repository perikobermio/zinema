#!/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
API_BASE_URL=${API_BASE_URL:-http://127.0.0.1:8180}
API_BASE_URL=${API_BASE_URL%/}

fail() {
    printf 'ERROR: %s\n' "$1" >&2
    exit 1
}

[ "$#" -le 1 ] || fail "Uso: $0 [ID_DEL_TRABAJO]"
command -v curl >/dev/null 2>&1 || fail "Falta curl."
[ -n "${DEVICE_TOKEN:-}" ] || fail "Define DEVICE_TOKEN con un token de una sesión administradora."

job_id=${1:-}
if [ -n "$job_id" ]; then
    case "$job_id" in
        *[!a-fA-F0-9]*) fail "El ID del trabajo debe contener solo caracteres hexadecimales." ;;
    esac
    [ "${#job_id}" -eq 32 ] || fail "El ID del trabajo debe tener 32 caracteres."
    endpoint="$API_BASE_URL/api/v1/admin/sync/$job_id"
else
    endpoint="$API_BASE_URL/api/v1/admin/sync"
fi

if response=$(printf 'Authorization: Bearer %s\n' "$DEVICE_TOKEN" | \
    curl --silent --show-error --fail-with-body --max-time 30 \
    --header @- "$endpoint"); then
    unset DEVICE_TOKEN
else
    unset DEVICE_TOKEN
    [ -n "$response" ] && printf '%s\n' "$response" >&2
    fail "No se pudo consultar el estado. Comprueba la URL, el token y que el servidor esté activo."
fi

if command -v jq >/dev/null 2>&1; then
    printf '%s\n' "$response" | jq .
else
    printf '%s\n' "$response"
fi
