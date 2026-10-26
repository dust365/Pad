#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
SDK=${ANDROID_SDK_ROOT:-"$HOME/Library/Android/sdk"}
BT="$SDK/build-tools/34.0.0"
ANDROID_JAR="$SDK/platforms/android-21/android.jar"
OUT="$ROOT/build"
SERIAL=${ANDROID_SERIAL:-20080411}

rm -rf "$OUT"
mkdir -p "$OUT/classes"

"$BT/aapt" package -f -M "$ROOT/AndroidManifest.xml" -S "$ROOT/res" -I "$ANDROID_JAR" -F "$OUT/unsigned.apk"

find "$ROOT/src" -name '*.java' > "$OUT/sources.txt"
javac --release 8 -encoding UTF-8 -classpath "$ANDROID_JAR" -d "$OUT/classes" @"$OUT/sources.txt"

"$BT/d8" --min-api 17 --lib "$ANDROID_JAR" --output "$OUT" "$OUT/classes/ai/pad/screen/"*.class

(
  cd "$OUT"
  "$BT/aapt" add unsigned.apk classes.dex >/dev/null
)
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f "$ROOT/debug.keystore" ]; then
  keytool -genkeypair -keystore "$ROOT/debug.keystore" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Pad Screen, OU=Pad, O=Pad, L=Local, ST=Local, C=CN"
fi

"$BT/apksigner" sign \
  --v1-signing-enabled true \
  --v2-signing-enabled false \
  --v3-signing-enabled false \
  --ks "$ROOT/debug.keystore" \
  --ks-pass pass:android \
  --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --out "$OUT/screen.apk" \
  "$OUT/aligned.apk"

"$BT/apksigner" verify --min-sdk-version 17 "$OUT/screen.apk"
echo "built $OUT/screen.apk"

if [ "${INSTALL:-1}" = "1" ]; then
  adb -s "$SERIAL" install -r "$OUT/screen.apk"
  # 只拉起第二核并切到 performance。不要写死 1056000，黑色这台最高是 1200000，写小了会降频。
  adb -s "$SERIAL" shell "echo 1 > /sys/devices/system/cpu/cpu1/online; echo performance > /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor; echo performance > /sys/devices/system/cpu/cpu1/cpufreq/scaling_governor" || true
  adb -s "$SERIAL" shell am start -n ai.pad.screen/.ScreenActivity
fi
