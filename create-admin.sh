#!/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$PROJECT_DIR"
command -v docker >/dev/null 2>&1 || { printf 'ERROR: falta docker.\n' >&2; exit 1; }
[ -f .env ] || { printf 'ERROR: falta .env.\n' >&2; exit 1; }

printf 'Este comando crea/promueve el administrador y asocia los perfiles sin propietario.\n'
printf 'La contraseña se solicita de forma oculta dentro del contenedor.\n\n'
docker compose exec -it zinema python -m app.create_admin
