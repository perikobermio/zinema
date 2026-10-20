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

[ -n "${MOVIES_PATH:-}" ] || fail "Falta MOVIES_PATH en .env."
[ -n "${SERIES_PATH:-}" ] || fail "Falta SERIES_PATH en .env."
[ -n "${INCOMING_PATH:-/d/NAS/Incoming}" ] || fail "Falta INCOMING_PATH en .env."
NAS_SCRIPTS_PATH=${NAS_SCRIPTS_PATH:-/d/NAS/scripts}
[ -d "$MOVIES_PATH" ] || fail "No existe la carpeta de películas: $MOVIES_PATH"
[ -r "$MOVIES_PATH" ] || fail "Docker/este usuario no puede leer: $MOVIES_PATH"
[ -w "$MOVIES_PATH" ] || fail "Docker/este usuario no puede escribir en: $MOVIES_PATH"
[ -d "$SERIES_PATH" ] || fail "No existe la carpeta de series: $SERIES_PATH"
[ -r "$SERIES_PATH" ] || fail "Docker/este usuario no puede leer: $SERIES_PATH"
[ -w "$SERIES_PATH" ] || fail "Docker/este usuario no puede escribir en: $SERIES_PATH"
[ -d "$INCOMING_PATH" ] || fail "No existe la carpeta Incoming: $INCOMING_PATH"
[ -r "$INCOMING_PATH" ] || fail "Docker/este usuario no puede leer: $INCOMING_PATH"
[ -w "$INCOMING_PATH" ] || fail "Docker/este usuario no puede escribir en: $INCOMING_PATH"
[ -d "$NAS_SCRIPTS_PATH" ] || fail "No existe la carpeta de scripts del NAS: $NAS_SCRIPTS_PATH"
[ -w "$NAS_SCRIPTS_PATH" ] || fail "Docker/este usuario no puede escribir en: $NAS_SCRIPTS_PATH"
[ -f "$NAS_SCRIPTS_PATH/telegram_download.py" ] || fail "No existe $NAS_SCRIPTS_PATH/telegram_download.py"
[ -r "$NAS_SCRIPTS_PATH/telegram_download.py" ] || fail "No se puede leer $NAS_SCRIPTS_PATH/telegram_download.py"
if [ -z "${TMDB_ACCESS_TOKEN:-}" ]; then
    printf 'AVISO: falta TMDB_ACCESS_TOKEN; el escaneo funcionará, pero la sincronización de metadata no.\n'
fi

cd "$PROJECT_DIR"
docker compose config -q || fail "La configuración de Docker Compose no es válida."

printf 'OK: Docker está activo y Compose v2 está disponible.\n'
printf 'OK: las bibliotecas, Incoming y los scripts existen y tienen los permisos necesarios.\n'
printf 'OK: la configuración de Docker Compose es válida.\n'
