#!/usr/bin/env bash
# Auto Skip - Linux (arm64 proot) 构建脚本
# 依赖：openjdk-17-jdk-headless、aapt、zipalign、apksigner（apt 安装）
# 用法：bash tools/build-linux.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AJ="${AJ:-/usr/lib/android-sdk/platforms/android-33/android.jar}"
D8="${D8:-$ROOT/build/bt/android-15/lib/d8.jar}"
OUT="$ROOT/dist/zhangtiaotiao-1.9.apk"

cd "$ROOT"
rm -rf build/gen build/out build/dex build/app-unsigned.apk build/app-aligned.apk
mkdir -p build/gen build/out build/dex dist

echo "== 1. aapt 生成 R.java + 资源包 =="
aapt package -f -m -J build/gen -M AndroidManifest.xml -S res -I "$AJ"
aapt package -f -M AndroidManifest.xml -S res -I "$AJ" -F build/app-unsigned.apk

echo "== 2. javac =="
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$AJ" -nowarn \
  -d build/out java/com/autoskip/app/*.java build/gen/com/autoskip/app/R.java

echo "== 3. d8 =="
java -cp "$D8" com.android.tools.r8.D8 --release --lib "$AJ" --min-api 24 \
  --output build/dex build/out/com/autoskip/app/*.class

echo "== 4. 打包 =="
(cd build/dex && aapt add ../app-unsigned.apk classes.dex >/dev/null)
zipalign -f -p 4 build/app-unsigned.apk build/app-aligned.apk

echo "== 5. keystore（不入库，首次运行时生成）=="
KS="$ROOT/keystore/autoskip.jks"
if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias autoskip -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Auto Skip, O=AutoSkip, C=CN" >/dev/null
  echo "已生成 $KS"
fi

echo "== 6. 签名 =="
apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$OUT" build/app-aligned.apk

echo "== 7. 校验 =="
apksigner verify --print-certs "$OUT" | head -3
ls -l "$OUT"
