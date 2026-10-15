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
BUILD_DIR="$CLIENT_DIR/build"
DEBUG_KEYSTORE="$CLIENT_DIR/.debug.keystore"

fail() {
    printf 'ERROR: %s\n' "$1" >&2
    exit 1
}

[ -x "$TOOLS/aapt2" ] || fail "No encuentro aapt2 en $TOOLS. Ajusta ANDROID_SDK_ROOT o BUILD_TOOLS_VERSION."
[ -x "$TOOLS/d8" ] || fail "No encuentro d8 en $TOOLS."
[ -x "$TOOLS/zipalign" ] || fail "No encuentro zipalign en $TOOLS."
[ -x "$TOOLS/apksigner" ] || fail "No encuentro apksigner en $TOOLS."
[ -f "$ANDROID_JAR" ] || fail "No encuentro $ANDROID_JAR. Ajusta ANDROID_API."
command -v javac >/dev/null 2>&1 || fail "Falta javac (instala un JDK)."
command -v jar >/dev/null 2>&1 || fail "Falta jar (instala un JDK)."
command -v zip >/dev/null 2>&1 || fail "Falta zip."
command -v keytool >/dev/null 2>&1 || fail "Falta keytool (instala un JDK)."

if [ ! -f "$DEBUG_KEYSTORE" ] && [ -f "$BUILD_DIR/debug.keystore" ]; then
    cp "$BUILD_DIR/debug.keystore" "$DEBUG_KEYSTORE"
fi
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/generated" "$BUILD_DIR/classes" "$BUILD_DIR/dex"
mkdir -p "$BUILD_DIR/outputs/apk/debug"
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
    --version-code 31 \
    --version-name 1.0.0 \
    --auto-add-overlay \
    "$BUILD_DIR/resources.zip"

find "$CLIENT_DIR/src/main/java" "$BUILD_DIR/generated" -name '*.java' -print > "$BUILD_DIR/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -classpath "$ANDROID_JAR" \
    -d "$BUILD_DIR/classes" @"$BUILD_DIR/sources.txt"
jar --create --file "$BUILD_DIR/client-classes.jar" -C "$BUILD_DIR/classes" .
"$TOOLS/d8" --lib "$ANDROID_JAR" --min-api 23 \
    --output "$BUILD_DIR/dex" "$BUILD_DIR/client-classes.jar"

cp "$BUILD_DIR/unsigned.apk" "$BUILD_DIR/with-dex.apk"
(
    cd "$BUILD_DIR/dex"
    zip -q "$BUILD_DIR/with-dex.apk" classes.dex
)
"$TOOLS/zipalign" -f 4 "$BUILD_DIR/with-dex.apk" "$BUILD_DIR/aligned.apk"

if [ ! -f "$DEBUG_KEYSTORE" ]; then
    keytool -genkeypair -noprompt \
        -keystore "$DEBUG_KEYSTORE" \
        -storepass android -keypass android -alias androiddebugkey \
        -dname "CN=Android Debug,O=Zinema,C=ES" \
        -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi
"$TOOLS/apksigner" sign \
    --ks "$DEBUG_KEYSTORE" \
    --ks-key-alias androiddebugkey \
    --ks-pass pass:android --key-pass pass:android \
    --out "$BUILD_DIR/outputs/apk/debug/zinema-client-debug.apk" \
    "$BUILD_DIR/aligned.apk"
"$TOOLS/apksigner" verify "$BUILD_DIR/outputs/apk/debug/zinema-client-debug.apk"
printf 'APK compilado: %s\n' "$BUILD_DIR/outputs/apk/debug/zinema-client-debug.apk"
