#!/bin/sh
set -eu

CLIENT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_DIR=$(CDPATH= cd -- "$CLIENT_DIR/.." && pwd)
CLIENT_HOME=${HOME:-}
SDK_ROOT=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$CLIENT_HOME/Android/Sdk}}
BUILD_TOOLS_VERSION=${BUILD_TOOLS_VERSION:-36.0.0}
ANDROID_API=${ANDROID_API:-35}
TOOLS="$SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION"
ANDROID_JAR="$SDK_ROOT/platforms/android-$ANDROID_API/android.jar"
R8_JAR="$SDK_ROOT/cmdline-tools/latest/lib/r8.jar"
BUILD_DIR="$CLIENT_DIR/build/release"
KEYSTORE="$CLIENT_DIR/.release.keystore"
SIGNING_ENV="$CLIENT_DIR/.release-signing.env"
OUTPUT="${1:-$REPO_DIR/zinema-prod.apk}"

fail() {
    printf 'ERROR: %s\n' "$1" >&2
    exit 1
}

[ -x "$TOOLS/aapt2" ] || fail "No encuentro aapt2 en $TOOLS."
[ -x "$TOOLS/zipalign" ] || fail "No encuentro zipalign en $TOOLS."
[ -x "$TOOLS/apksigner" ] || fail "No encuentro apksigner en $TOOLS."
[ -f "$ANDROID_JAR" ] || fail "No encuentro $ANDROID_JAR."
[ -f "$R8_JAR" ] || fail "No encuentro R8 en $R8_JAR."
command -v javac >/dev/null 2>&1 || fail "Falta javac (instala un JDK)."
command -v jar >/dev/null 2>&1 || fail "Falta jar."
command -v zip >/dev/null 2>&1 || fail "Falta zip."
command -v keytool >/dev/null 2>&1 || fail "Falta keytool."

mkdir -p "$BUILD_DIR/generated" "$BUILD_DIR/classes" "$BUILD_DIR/dex" \
    "$(dirname -- "$OUTPUT")" "$CLIENT_DIR/build/outputs/mapping/release"
rm -rf "$BUILD_DIR/generated" "$BUILD_DIR/classes" "$BUILD_DIR/dex"
mkdir -p "$BUILD_DIR/generated" "$BUILD_DIR/classes" "$BUILD_DIR/dex"
python3 "$CLIENT_DIR/generate_server_config.py" "$REPO_DIR/.env" \
    "$BUILD_DIR/generated/com/zinema/client/ServerConfig.java"

"$TOOLS/aapt2" compile --dir "$CLIENT_DIR/src/main/res" -o "$BUILD_DIR/resources.zip"
"$TOOLS/aapt2" link \
    -o "$BUILD_DIR/unsigned.apk" \
    --manifest "$CLIENT_DIR/src/main/AndroidManifest.xml" \
    -I "$ANDROID_JAR" \
    -A "$CLIENT_DIR/src/main/assets" \
    --java "$BUILD_DIR/generated" \
    --custom-package com.zinema.client \
    --min-sdk-version 23 \
    --target-sdk-version "$ANDROID_API" \
    --version-code 32 \
    --version-name 1.0.0 \
    --auto-add-overlay \
    "$BUILD_DIR/resources.zip"

find "$CLIENT_DIR/src/main/java" "$BUILD_DIR/generated" -name '*.java' -print > "$BUILD_DIR/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -classpath "$ANDROID_JAR" \
    -d "$BUILD_DIR/classes" @"$BUILD_DIR/sources.txt"
jar --create --file "$BUILD_DIR/client-classes.jar" -C "$BUILD_DIR/classes" .

java -cp "$R8_JAR" com.android.tools.r8.R8 \
    --release \
    --min-api 23 \
    --lib "$ANDROID_JAR" \
    --pg-conf "$CLIENT_DIR/proguard-rules.pro" \
    --pg-map-output "$CLIENT_DIR/build/outputs/mapping/release/mapping.txt" \
    --output "$BUILD_DIR/dex" \
    "$BUILD_DIR/client-classes.jar"

cp "$BUILD_DIR/unsigned.apk" "$BUILD_DIR/with-dex.apk"
(
    cd "$BUILD_DIR/dex"
    zip -q "$BUILD_DIR/with-dex.apk" classes.dex
)
"$TOOLS/zipalign" -f 4 "$BUILD_DIR/with-dex.apk" "$BUILD_DIR/aligned.apk"

if [ ! -f "$SIGNING_ENV" ]; then
    if [ -f "$KEYSTORE" ]; then
        fail "Existe $KEYSTORE pero falta $SIGNING_ENV. Restaura la contraseña guardada de la clave de distribución."
    fi
    PASSWORD_FILE="$BUILD_DIR/signing-password"
    umask 077
    if command -v openssl >/dev/null 2>&1; then
        openssl rand -hex 32 > "$PASSWORD_FILE"
    else
        fail "Falta openssl para crear de forma segura la contraseña de la clave de distribución."
    fi
    IFS= read -r RELEASE_PASSWORD < "$PASSWORD_FILE"
    rm -f "$PASSWORD_FILE"
    {
        printf 'RELEASE_KEY_ALIAS=zinema-release\n'
        printf 'RELEASE_STORE_PASSWORD=%s\n' "$RELEASE_PASSWORD"
        printf 'RELEASE_KEY_PASSWORD=%s\n' "$RELEASE_PASSWORD"
    } > "$SIGNING_ENV"
    chmod 600 "$SIGNING_ENV"
fi

set -a
. "$SIGNING_ENV"
set +a
[ -n "${RELEASE_STORE_PASSWORD:-}" ] || fail "Falta RELEASE_STORE_PASSWORD en $SIGNING_ENV."
[ -n "${RELEASE_KEY_PASSWORD:-}" ] || fail "Falta RELEASE_KEY_PASSWORD en $SIGNING_ENV."
RELEASE_KEY_ALIAS=${RELEASE_KEY_ALIAS:-zinema-release}

if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -noprompt \
        -keystore "$KEYSTORE" \
        -storepass "$RELEASE_STORE_PASSWORD" \
        -keypass "$RELEASE_KEY_PASSWORD" \
        -alias "$RELEASE_KEY_ALIAS" \
        -dname "CN=Zinema Release,O=Zinema,C=ES" \
        -keyalg RSA -keysize 3072 -validity 10000 >/dev/null 2>&1
    chmod 600 "$KEYSTORE"
fi

"$TOOLS/apksigner" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias "$RELEASE_KEY_ALIAS" \
    --ks-pass env:RELEASE_STORE_PASSWORD \
    --key-pass env:RELEASE_KEY_PASSWORD \
    --v4-signing-enabled false \
    --out "$OUTPUT" \
    "$BUILD_DIR/aligned.apk"
"$TOOLS/apksigner" verify --verbose "$OUTPUT"
printf 'APK de distribución: %s\n' "$OUTPUT"
wc -c "$OUTPUT"
printf 'Clave de distribución: %s (guarda una copia segura junto con %s)\n' "$KEYSTORE" "$SIGNING_ENV"
