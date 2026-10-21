#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
SDK=${ANDROID_SDK_ROOT:-"$HOME/Library/Android/sdk"}
BT="$SDK/build-tools/34.0.0"
ANDROID_JAR="$SDK/platforms/android-21/android.jar"
OUT="$ROOT/build"
WWW="$ROOT/www"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$WWW"

build_one() {
  name=$1
  manifest=$2
  mode=$3
  stage="$OUT/$name-manifest"
  rm -rf "$stage"
  mkdir -p "$stage"
  cp "$manifest" "$stage/AndroidManifest.xml"
  "$BT/aapt" package -f -M "$stage/AndroidManifest.xml" -I "$ANDROID_JAR" -F "$OUT/$name-unsigned.apk"
  find "$ROOT/src" -name '*.java' > "$OUT/sources.txt"
  javac --release 8 -encoding UTF-8 -classpath "$ANDROID_JAR" -d "$OUT/classes" @"$OUT/sources.txt"
  "$BT/d8" --min-api 17 --lib "$ANDROID_JAR" --output "$OUT" "$OUT/classes/ai/pad/netadb/"*.class
  (
    cd "$OUT"
    "$BT/aapt" add "$name-unsigned.apk" classes.dex >/dev/null
  )
  "$BT/zipalign" -f 4 "$OUT/$name-unsigned.apk" "$OUT/$name-aligned.apk"
  if [ "$mode" = "platform" ]; then
    "$BT/apksigner" sign \
      --v1-signing-enabled true \
      --v2-signing-enabled false \
      --v3-signing-enabled false \
      --key "$ROOT/keys/platform.pk8" \
      --cert "$ROOT/keys/platform.x509.pem" \
      --out "$WWW/$name.apk" \
      "$OUT/$name-aligned.apk"
  else
    if [ ! -f "$ROOT/debug.keystore" ]; then
      keytool -genkeypair -keystore "$ROOT/debug.keystore" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Pad NetAdb, OU=Pad, O=Pad, L=Local, ST=Local, C=CN"
    fi
    "$BT/apksigner" sign \
      --v1-signing-enabled true \
      --v2-signing-enabled false \
      --v3-signing-enabled false \
      --ks "$ROOT/debug.keystore" \
      --ks-pass pass:android \
      --key-pass pass:android \
      --ks-key-alias androiddebugkey \
      --out "$WWW/$name.apk" \
      "$OUT/$name-aligned.apk"
  fi
  "$BT/apksigner" verify --min-sdk-version 17 "$WWW/$name.apk"
  echo "built $WWW/$name.apk"
}

build_one netadb "$ROOT/AndroidManifest.xml" platform
build_one netadb-user "$ROOT/AndroidManifest-user.xml" debug
