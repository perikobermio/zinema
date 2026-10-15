#!/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
API_BASE_URL=${API_BASE_URL:-http://127.0.0.1:8180}
API_BASE_URL=${API_BASE_URL%/}
UPDATE_DIR="$PROJECT_DIR/data/client-update"
APK="$PROJECT_DIR/zinema-prod.apk"
MANIFEST="$PROJECT_DIR/zinema-update.json"

fail() {
    printf 'ERROR: %s\n' "$1" >&2
    exit 1
}

[ -f "$APK" ] || fail "Falta $APK. Ejecuta la compilación de producción y sube el APK a GitHub."
[ -f "$MANIFEST" ] || fail "Falta $MANIFEST. Sube también el manifiesto generado con el APK."
command -v python3 >/dev/null 2>&1 || fail "Falta python3."
command -v docker >/dev/null 2>&1 || fail "Falta Docker."
command -v curl >/dev/null 2>&1 || fail "Falta curl."

python3 - "$APK" "$MANIFEST" <<'PY'
import hashlib
import json
import pathlib
import sys

apk = pathlib.Path(sys.argv[1])
manifest_path = pathlib.Path(sys.argv[2])
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
digest = hashlib.sha256(apk.read_bytes()).hexdigest()
if int(manifest.get("version_code", 0)) < 1:
    raise SystemExit("El manifiesto no contiene un version_code válido.")
if int(manifest.get("size_bytes", 0)) != apk.stat().st_size:
    raise SystemExit("El tamaño del APK no coincide con zinema-update.json.")
if manifest.get("sha256", "").lower() != digest:
    raise SystemExit("El SHA-256 del APK no coincide con zinema-update.json.")
print("Artefactos válidos: versión", manifest.get("version_name", manifest["version_code"]))
PY

cd "$PROJECT_DIR"
docker compose up --build --force-recreate -d zinema

# Wait for the newly deployed update endpoint before publishing the APK.
attempt=0
while [ "$attempt" -lt 30 ]; do
    response=$(curl --silent --show-error --fail --max-time 5 \
        "$API_BASE_URL/api/v1/client/update" 2>/dev/null || true)
    if printf '%s' "$response" | python3 -c \
        'import json,sys; d=json.load(sys.stdin); sys.exit(0 if "version_code" in d else 1)' \
        >/dev/null 2>&1; then
        break
    fi
    attempt=$((attempt + 1))
    sleep 2
done
[ "$attempt" -lt 30 ] || fail "La API nueva no respondió en $API_BASE_URL. Comprueba Docker y la URL."

mkdir -p "$UPDATE_DIR"
apk_tmp="$UPDATE_DIR/.zinema-prod.apk.$$"
manifest_tmp="$UPDATE_DIR/.manifest.json.$$"
trap 'rm -f "$apk_tmp" "$manifest_tmp"' EXIT HUP INT TERM
install -m 0644 "$APK" "$apk_tmp"
install -m 0644 "$MANIFEST" "$manifest_tmp"
mv -f "$apk_tmp" "$UPDATE_DIR/zinema-prod.apk"
# Publish metadata last so clients never see a manifest for a partial APK upload.
mv -f "$manifest_tmp" "$UPDATE_DIR/manifest.json"

response=$(curl --silent --show-error --fail --max-time 15 \
    "$API_BASE_URL/api/v1/client/update") || fail "No se pudo comprobar la publicación en la API."
python3 -c 'import json,sys; actual=json.loads(sys.argv[1]); expected=json.load(open(sys.argv[2], encoding="utf-8")); keys=("version_code","version_name","sha256","size_bytes"); mismatches=[k for k in keys if actual.get(k) != expected.get(k)]; print("ERROR: la API no publicó correctamente: " + ", ".join(mismatches), file=sys.stderr) if mismatches else print("Actualización publicada:", actual["version_name"], "(versionCode", actual["version_code"], ")"); sys.exit(bool(mismatches))' \
    "$response" "$MANIFEST"
printf 'Los clientes verán el aviso al abrir o volver a usar la app.\n'
