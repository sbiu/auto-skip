#!/usr/bin/env bash
# AutoSkip TestAd - Linux (arm64 proot) 构建脚本
# 依赖：openjdk-17-jdk-headless、aapt、zipalign、apksigner
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/testapp"
AJ="${AJ:-/usr/lib/android-sdk/platforms/android-33/android.jar}"
D8="${D8:-$ROOT/build/bt/android-15/lib/d8.jar}"
OUT="$APP/dist/testad.apk"

cd "$APP"
rm -rf build && mkdir -p build/gen build/out build/dex dist

echo "== 1. aapt 生成 R.java + 资源包 =="
aapt package -f -m -J build/gen -M AndroidManifest.xml -S res -I "$AJ"
aapt package -f -M AndroidManifest.xml -S res -I "$AJ" -F build/app-unsigned.apk

echo "== 2. javac =="
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$AJ" -nowarn \
  -d build/out java/com/autoskip/testad/*.java build/gen/com/autoskip/testad/R.java

echo "== 3. d8 =="
java -cp "$D8" com.android.tools.r8.D8 --release --lib "$AJ" --min-api 24 \
  --output build/dex build/out/com/autoskip/testad/*.class

echo "== 4. 打包 =="
(cd build/dex && aapt add ../app-unsigned.apk classes.dex >/dev/null)
zipalign -f -p 4 build/app-unsigned.apk build/app-aligned.apk

echo "== 5. 签名 =="
apksigner sign --ks "$ROOT/keystore/autoskip.jks" --ks-pass pass:android --key-pass pass:android \
  --out "$OUT" build/app-aligned.apk
ls -l "$OUT"
