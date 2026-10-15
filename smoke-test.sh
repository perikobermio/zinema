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
"$PROJECT_DIR/check-nas.sh"
cd "$PROJECT_DIR"

printf '\nArrancando Zinema (la primera vez puede tardar mientras se construye)...\n'
docker compose up --build -d

tmp_dir=$(mktemp -d)
cleanup() {
    rm -rf "$tmp_dir"
}
trap cleanup EXIT HUP INT TERM

ready=0
attempt=0
while [ "$attempt" -lt 60 ]; do
    if curl --silent --show-error --fail --max-time 3 "$API_BASE_URL/health" >"$tmp_dir/health" 2>/dev/null; then
        ready=1
        break
    fi
    attempt=$((attempt + 1))
    sleep 2
done
[ "$ready" -eq 1 ] || {
    docker compose logs --tail=80 zinema >&2
    fail "El servicio no respondió en $API_BASE_URL/health tras 120 segundos."
}
printf 'OK: health responde: '
cat "$tmp_dir/health"
printf '\n'

[ -n "${DEVICE_TOKEN:-}" ] || fail "Define DEVICE_TOKEN con un token de una sesión administradora."

printf 'Escaneando las bibliotecas; con muchos vídeos puede tardar...\n'
scan_response=$(printf 'Authorization: Bearer %s\n' "$DEVICE_TOKEN" | \
    curl --silent --show-error --fail-with-body --max-time 0 \
    --request POST --header @- "$API_BASE_URL/api/v1/admin/scan") || \
    fail "Falló el escaneo. Revisa las rutas y los logs de Docker."
printf 'OK: respuesta del escaneo: %s\n' "$scan_response"

items_response=$(curl --silent --show-error --fail \
    --header "Authorization: Bearer $DEVICE_TOKEN" \
    "$API_BASE_URL/api/v1/items?limit=1") || fail "No se pudo consultar el catálogo."
printf 'OK: consulta del catálogo: %s\n' "$items_response"

item_id=$(printf '%s\n' "$items_response" | sed -n 's/.*"id":"\([a-fA-F0-9][a-fA-F0-9]*\)".*/\1/p')
if [ -z "$item_id" ]; then
    printf 'AVISO: el catálogo está vacío; no hay vídeo para probar el streaming.\n'
    exit 0
fi

ticket_response=$(curl --silent --show-error --fail-with-body \
    --request POST --header "Authorization: Bearer $DEVICE_TOKEN" \
    "$API_BASE_URL/api/v1/items/$item_id/playback-ticket") || fail "No se pudo emitir el acceso temporal de streaming."
ticket_path=$(printf '%s\n' "$ticket_response" | sed -n 's/.*"url":"\([^"]*\)".*/\1/p')
[ -n "$ticket_path" ] || fail "La API no devolvió la URL temporal de streaming."

status=$(curl --silent --show-error --output /dev/null \
    --dump-header "$tmp_dir/stream-headers" --write-out '%{http_code}' \
    --header 'Range: bytes=0-0' \
    "$API_BASE_URL$ticket_path") || fail "Falló la petición de streaming."
if [ "$status" = "206" ] && grep -Eiq '^Content-Range: bytes 0-0/[1-9][0-9]*' "$tmp_dir/stream-headers"; then
    printf 'OK: streaming acepta rangos de bytes (HTTP 206).\n'
elif [ "$status" = "416" ] && grep -Eiq '^Content-Range: bytes \*/0' "$tmp_dir/stream-headers"; then
    printf 'AVISO: el primer vídeo del catálogo está vacío; se omitió la prueba de rangos.\n'
else
    cat "$tmp_dir/stream-headers" >&2
    fail "La prueba de streaming esperaba HTTP 206 y recibió HTTP $status."
fi

printf '\nTodas las comprobaciones han terminado. Desde otro dispositivo prueba http://IP_DEL_NAS:8180/health\n'
