#!/bin/sh
# 编译安卓手机上的发送端。默认只产出 apk，不安装。
# 安装必须显式给出手机序列号，平板接收端的序列号会被拒绝。
set -eu
ROOT=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
SDK=${ANDROID_SDK_ROOT:-"$HOME/Library/Android/sdk"}
BT="$SDK/build-tools/34.0.0"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
OUT="$ROOT/build"
ADB="$SDK/platform-tools/adb"

if [ ! -f "$ANDROID_JAR" ]; then
  echo "缺少 $ANDROID_JAR" >&2
  exit 1
fi
if [ ! -x "$ADB" ]; then
  ADB=adb
fi

if [ "${INSTALL:-0}" = "1" ]; then
  if [ -z "${ANDROID_SERIAL:-}" ]; then
    echo "安装到手机必须设置 ANDROID_SERIAL" >&2
    exit 1
  fi
  case "$ANDROID_SERIAL" in
    20080411|192.168.66.127:5555|192.168.66.147:5555)
      echo "拒绝安装到平板 ${ANDROID_SERIAL}。发送端要装到手机上。" >&2
      exit 1
      ;;
  esac
fi

rm -rf "$OUT"
mkdir -p "$OUT/classes"

"$BT/aapt" package -f -M "$ROOT/AndroidManifest.xml" -S "$ROOT/res" -I "$ANDROID_JAR" -F "$OUT/unsigned.apk"

find "$ROOT/src" -name '*.java' > "$OUT/sources.txt"
javac --release 8 -encoding UTF-8 -classpath "$ANDROID_JAR" -d "$OUT/classes" @"$OUT/sources.txt"

"$BT/d8" --min-api 26 --lib "$ANDROID_JAR" --output "$OUT" "$OUT/classes/ai/pad/screen/send/"*.class

(
  cd "$OUT"
  "$BT/aapt" add unsigned.apk classes.dex >/dev/null
)
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f "$ROOT/debug.keystore" ]; then
  keytool -genkeypair -keystore "$ROOT/debug.keystore" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Pad Screen Send, OU=Pad, O=Pad, L=Local, ST=Local, C=CN"
fi

"$BT/apksigner" sign \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --v3-signing-enabled false \
  --ks "$ROOT/debug.keystore" \
  --ks-pass pass:android \
  --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --out "$OUT/send.apk" \
  "$OUT/aligned.apk"

"$BT/apksigner" verify --min-sdk-version 26 "$OUT/send.apk"
echo "built $OUT/send.apk"

if [ "${INSTALL:-0}" = "1" ]; then
  "$ADB" -s "$ANDROID_SERIAL" install -r "$OUT/send.apk"
  "$ADB" -s "$ANDROID_SERIAL" shell am start -n ai.pad.screen.send/.MainActivity
fi
