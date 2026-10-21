#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
APP="$ROOT/build/PadScreen.app"
MACOS="$APP/Contents/MacOS"
INSTALLED="/Applications/副屏.app"
# 正在运行时不能替换包里的可执行文件。
if pgrep -f "PadScreen.app/Contents/MacOS/PadScreen" >/dev/null 2>&1; then
  killall PadScreen 2>/dev/null || true
  sleep 0.3
fi
rm -rf "$APP"
mkdir -p "$MACOS" "$APP/Contents/Resources"
cp "$ROOT/Info.plist" "$APP/Contents/Info.plist"

ICONSET="$ROOT/build/AppIcon.iconset"
rm -rf "$ICONSET"
mkdir -p "$ICONSET"
sips -z 16 16 "$ROOT/AppIcon.png" --out "$ICONSET/icon_16x16.png" >/dev/null
sips -z 32 32 "$ROOT/AppIcon.png" --out "$ICONSET/icon_16x16@2x.png" >/dev/null
sips -z 32 32 "$ROOT/AppIcon.png" --out "$ICONSET/icon_32x32.png" >/dev/null
sips -z 64 64 "$ROOT/AppIcon.png" --out "$ICONSET/icon_32x32@2x.png" >/dev/null
sips -z 128 128 "$ROOT/AppIcon.png" --out "$ICONSET/icon_128x128.png" >/dev/null
sips -z 256 256 "$ROOT/AppIcon.png" --out "$ICONSET/icon_128x128@2x.png" >/dev/null
sips -z 256 256 "$ROOT/AppIcon.png" --out "$ICONSET/icon_256x256.png" >/dev/null
sips -z 512 512 "$ROOT/AppIcon.png" --out "$ICONSET/icon_256x256@2x.png" >/dev/null
sips -z 512 512 "$ROOT/AppIcon.png" --out "$ICONSET/icon_512x512.png" >/dev/null
sips -z 1024 1024 "$ROOT/AppIcon.png" --out "$ICONSET/icon_512x512@2x.png" >/dev/null
iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/AppIcon.icns"

SDK=$(xcrun --show-sdk-path)
clang -fobjc-arc -isysroot "$SDK" \
  -framework Foundation -framework AppKit -framework CoreGraphics \
  -c "$ROOT/VirtualDisplay.m" -o "$ROOT/build/VirtualDisplay.o"

swiftc -swift-version 5 -O -sdk "$SDK" \
  -framework Cocoa -framework ScreenCaptureKit -framework CoreMedia \
  -framework CoreImage -framework ImageIO -framework UniformTypeIdentifiers \
  -framework ApplicationServices -framework CoreGraphics \
  -import-objc-header "$ROOT/VirtualDisplay.h" \
  "$ROOT/Wire.swift" "$ROOT/Finder.swift" "$ROOT/AppMain.swift" \
  "$ROOT/build/VirtualDisplay.o" \
  -o "$MACOS/PadScreen"

# 固定证书签名。临时签名每次编译都会换哈希，系统会把录屏权限当成新程序清掉。
CERT="PadScreen Local"
KEYCHAIN="$HOME/Library/Keychains/login.keychain-db"
if ! security find-identity -v -p codesigning | grep -q "\"$CERT\""; then
  "$ROOT/ensure-cert.sh"
fi
codesign --force --sign "$CERT" --keychain "$KEYCHAIN" "$APP"
rm -rf "$INSTALLED"
ditto "$APP" "$INSTALLED"
codesign --verify --strict "$INSTALLED"
LSREGISTER="/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister"
"$LSREGISTER" -f "$INSTALLED"
echo "built $INSTALLED"
