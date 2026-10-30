#!/bin/bash
# 口袋CPM 测试版 APK 构建脚本（不用 Gradle，手工走 aapt2/javac/d8/apksigner）
set -e

# 工程实际位置：D:\口袋CPM\project（工作区内）。
# 但 aapt2 / javac 这些 C++/JVM 工具无法处理非 ASCII 路径，
# 所以统一通过 ASCII 目录联接 D:\kcpm 访问（文件仍在工作区内，别名只是入口）。
# 重建联接：powershell -NoProfile -Command "New-Item -ItemType Junction -Path 'D:\kcpm' -Target 'D:\口袋CPM\project'"
ROOT=${KCPM_ROOT:-/d/kcpm}

if [ ! -d "$ROOT/app" ]; then
  echo "错误：找不到工程目录 $ROOT"
  echo "工程实际在 D:\\口袋CPM\\project。请先创建 ASCII 别名："
  echo "  powershell -NoProfile -Command \"New-Item -ItemType Junction -Path 'D:\\kcpm' -Target 'D:\\口袋CPM\\project'\""
  exit 1
fi

APP=$ROOT/app
SDK=$ROOT/sdk
BT=$SDK/bt/android-14
AJ=$SDK/platform35/android-35/android.jar
JDK=$ROOT/jdk-17.0.20.1+1
JAVA=$JDK/bin/java.exe
JAVAC=$JDK/bin/javac.exe
KEYTOOL=$JDK/bin/keytool.exe
OUT=$ROOT/out
TARGET_SDK=${TARGET_SDK:-34}
OUT_NAME=${OUT_NAME:-koudai-cpm-0.1-test.apk}

export JAVA_HOME=$JDK
export PATH="$JDK/bin:$PATH"

mkdir -p "$ROOT/build"
if [ -d "$ROOT/build" ]; then find "$ROOT/build" -mindepth 1 -delete 2>/dev/null || true; fi
mkdir -p "$ROOT/build/classes" "$ROOT/build/dex" "$OUT"

echo "[1/6] aapt2 compile 资源"
"$BT/aapt2.exe" compile --dir "$APP/res" -o "$ROOT/build/res.zip"

echo "[2/6] aapt2 link 生成资源包"
sed "s|android:targetSdkVersion=\"[0-9]*\"|android:targetSdkVersion=\"$TARGET_SDK\"|" \
  "$APP/AndroidManifest.xml" > "$ROOT/build/AndroidManifest.xml"
mkdir -p "$ROOT/build/gen"
"$BT/aapt2.exe" link \
  -o "$ROOT/build/base.apk" \
  -I "$AJ" \
  --manifest "$ROOT/build/AndroidManifest.xml" \
  -R "$ROOT/build/res.zip" \
  --java "$ROOT/build/gen" \
  --auto-add-overlay

echo "[3/6] javac 编译 Java"
# sherpa-onnx 的 Java API 里有 lambda，用 android.jar 当引导类路径会找不到 LambdaMetafactory，
# 所以先按普通 JVM 编出语音层，再用 android.jar 编 App 代码并把它放进 classpath。
SPEECH_SRC=$ROOT/speech/src/sherpa-onnx/java-api/src/main/java
find "$SPEECH_SRC" -name '*.java' | sed 's|^/d/|D:/|' > "$ROOT/build/speech-sources.txt"
"$JAVAC" -nowarn -encoding UTF-8 -source 8 -target 8 \
  -d "$ROOT/build/classes" @"$ROOT/build/speech-sources.txt"
find "$APP/src" "$ROOT/build/gen" -name '*.java' | sed 's|^/d/|D:/|' > "$ROOT/build/sources.txt"
"$JAVAC" -nowarn -encoding UTF-8 -source 8 -target 8 -bootclasspath "$AJ" \
  -classpath "$ROOT/build/classes" \
  -d "$ROOT/build/classes" @"$ROOT/build/sources.txt"

echo "[4/6] d8 转 dex"
find "$ROOT/build/classes" -name '*.class' | sed 's|^/d/|D:/|' > "$ROOT/build/classes.txt"
"$JAVA" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --lib "$AJ" --min-api 28 \
  --output "$ROOT/build/dex" @"$ROOT/build/classes.txt"

echo "[5/6] 组装 APK（资源 + dex + assets + 原生库）"
python "$ROOT/app/pack.py"

echo "[6/6] zipalign + 签名"
# 签名密钥必须固定放在 build/ 之外：build/ 每次构建都会被清空，
# 以前放在里面会导致每次重新生成密钥，签名变化后无法覆盖安装。
KS="$ROOT/app/koudai.keystore"
if [ ! -f "$KS" ]; then
  "$KEYTOOL" -genkeypair -keystore "$KS" \
    -alias koudai -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass koudai123 -keypass koudai123 \
    -dname "CN=Koudai CPM, OU=Lab, O=Test, L=CN, C=CN"
fi
SIZE=$(stat -c %s "$ROOT/build/unaligned.apk")
if [ "$SIZE" -lt 2100000000 ]; then
  "$BT/zipalign.exe" -f -p 4 "$ROOT/build/unaligned.apk" "$OUT/$OUT_NAME"
else
  echo "包 $SIZE 字节，超过 zipalign/apksigner 的 2GB 上限，直接使用已对齐的包"
  cp "$ROOT/build/unaligned.apk" "$OUT/$OUT_NAME"
fi
"$JAVA" -jar "$BT/lib/apksigner.jar" sign \
  --v1-signing-enabled true --v2-signing-enabled true \
  --v3-signing-enabled true --v4-signing-enabled false \
  --ks "$KS" --ks-pass pass:koudai123 \
  --key-pass pass:koudai123 --ks-key-alias koudai \
  "$OUT/$OUT_NAME"

echo "完成：$OUT/$OUT_NAME"
ls -lh "$OUT/$OUT_NAME"
