#!/bin/sh
# 无 Android SDK 构建链路（Java + Kotlin 混合）
#   kotlinc(纯 Kotlin) -> R8 -> 手编 AXML -> zip -> 签名
# 用法: sh tools/build2.sh [new|legacy]
set -e
MODE="${1:-new}"

cd "$(dirname "$0")/.."
ROOT=$(pwd)
DL=${DL:-$ROOT/.tools}
KOTLINC=${KOTLINC:-$DL/kotlin/kotlinc/bin/kotlinc}
export DL
OUT=build/$MODE

if [ "$MODE" = "new" ]; then
  XAPI="$DL/lxaar/classes.jar:$DL/lxsvc/classes.jar"
  MINVER=102
  ENTRY="io.github.tancan1998.uxian.NewEntry"
else
  XAPI="$DL/api82.jar"
  MINVER=93
  ENTRY="io.github.tancan1998.uxian.HookEntry"
fi
CP="$DL/android-all.jar:$XAPI:$DL/nanohttpd.jar"
KSTDLIB=${KSTDLIB:-$(dirname "$KOTLINC")/../lib/kotlin-stdlib.jar}

echo "== 1. 注入构建时间戳 ($MODE)"
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/assets" "$OUT/META-INF/xposed"

STAMP=$(date +%m%d-%H%M%S)
BT=app/src/main/kotlin/io/github/tancan1998/uxian/BuildTag.kt
python3 - "$BT" "$STAMP" <<'PY'
import re, sys
p, stamp = sys.argv[1], sys.argv[2]
s = open(p).read()
s = re.sub(r'(var STAMP: String = ")[^"]*(")',
           lambda m: m.group(1) + stamp + m.group(2), s, count=1)
open(p, 'w').write(s)
PY
echo "   build stamp: $STAMP"

echo "== 1.5 内嵌前端资源（assets -> Kotlin 常量）"
# 必须在这里做，不能靠人记得手动跑。
# APK 没有 resources.arsc，读不到 assets 目录，模板/脚本/词典全是 Kotlin 常量。
# 之前靠手动执行，改完前端忘了跑，结果装机后跑的还是旧界面（踩过）。
python3 tools/embed_assets.py

echo "== 2. kotlinc（纯 Kotlin，工程里已无 Java 源）"
# 按模式收集：main 永远编，模式目录存在才加
KT_SRC=$(rg --files app/src/main/kotlin -g "*.kt" || true)
if [ -d "app/src/$MODE/kotlin" ]; then
  KT_SRC="$KT_SRC $(rg --files "app/src/$MODE/kotlin" -g "*.kt" || true)"
fi
if [ -z "$KT_SRC" ]; then
  echo "!! 没找到 Kotlin 源文件" >&2
  exit 1
fi
# -Xsam-conversions=class / -Xlambdas=class：让编译器为每个 lambda 生成
# 显式类，而不是用 invokedynamic。R8 处理 indy 生成的 lambda 有 bug，
# 会把 run() 变成抽象方法，运行时抛 AbstractMethodError 让宿主闪退。
#
# 性能：不走 kotlinc 的 bash 包装脚本（我们这里是 ash，兼容层很慢，
# 20s -> 12s），直接调 java 主类。TieredStopAtLevel=1 让 JIT 只做一层
# 编译 —— 短命进程不值得深度优化，这一步省掉约 1/3 时间。
KLIB=$(dirname "$KOTLINC")/../lib
java -Xmx2g -XX:TieredStopAtLevel=1 \
  -cp "$KLIB/kotlin-compiler.jar:$KLIB/kotlin-stdlib.jar:$KLIB/kotlin-reflect.jar:$KLIB/kotlin-script-runtime.jar:$KLIB/annotations-13.0.jar" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -nowarn -jvm-target 17 -no-reflect \
  -Xlambdas=class -Xsam-conversions=class \
  -classpath "$CP" \
  -d "$OUT/classes" $KT_SRC > "$OUT/kotlinc.log" 2>&1
KT_RC=$?
# 以前这里写成 `| rg -i 'error' || true`，编译错误被吞掉，脚本带着空 classes
# 继续打包签名，产出一个能装但根本不含代码的 APK（41KB vs 正常 213KB）。
# 装机后表现为"模块好像没生效"，排查方向完全错。必须硬失败。
if [ $KT_RC -ne 0 ] || rg -qi 'error:' "$OUT/kotlinc.log"; then
  echo "!! kotlinc 编译失败（rc=$KT_RC）" >&2
  rg -i 'error' "$OUT/kotlinc.log" | head -20 >&2
  exit 1
fi

echo "== 3. R8（收缩 + 优化 + dex 化）"
if [ "$MODE" = "new" ]; then
  EXTRA_CP="--classpath $DL/lxaar/classes.jar --classpath $DL/lxsvc/classes.jar"
else
  EXTRA_CP=""
fi
# annotations jar 也喂进去，R8 才能解析 @NotNull/@Nullable
ANN_JAR="$(dirname "$KOTLINC")/../lib/annotations-13.0.jar"
ANN_CP=""
[ -f "$ANN_JAR" ] && ANN_CP="--classpath $ANN_JAR"

# 关键前提：tools/keep.r8 里必须 -keep class kotlin.** { *; }。
# 踩过的坑：R8 的优化会重写 kotlin-stdlib 的迭代器实现，产出
# AbstractMethodError: "boolean java.util.Iterator.hasNext()"，
# 宿主进程里所有 for 循环抛错、hook 一个都装不上（实测 instanceHooks=0）。
# 把 kotlin 包整体 keep 住之后，R8 只收缩我们自己的代码，实测钩子全正常。
# 不加 --release：R8 的 full-mode 优化会破坏 Kotlin 的 SAM 转换
# （lambda -> Runnable），运行时抛 AbstractMethodError 让宿主闪退。
# 关掉优化通道后只做收缩 + dex 化，语义正确。
# R8 做收缩 + 优化 + dex 化。
#
# 关键前提（两个都必须有，缺一个就闪退）：
#   1. kotlinc 加 -Xlambdas=class -Xsam-conversions=class
#      让编译器为每个 lambda 生成显式类，不用 invokedynamic。
#      R8 处理 indy 生成的 lambda 有 bug，会把 run() 变成抽象方法，
#      运行时抛 AbstractMethodError: "void java.lang.Runnable.run()"
#      on receiver java.lang.Class<s1> —— 宿主直接闪退。
#   2. tools/keep.r8 里 -keep class kotlin.** { *; }
# libxposed 的 XposedProvider 必须**作为输入**传进来，不能只放 classpath。
# 踩过的坑：它声明在 AndroidManifest 里，但代码没有直接引用，
# 只当 classpath 的话 R8 认为「这是库类」不会打包 ——
# 结果中转页进程启动时 ClassNotFoundException 崩溃：
#   Unable to get provider io.github.libxposed.service.XposedProvider
# 表现为「重启按钮点了没反应」（RestartActivity 起不来）。
LIBXP_INPUT=""
if [ "$MODE" = "new" ]; then
  # 解开 lxsvc，只取 service 包的 class 作为输入
  rm -rf "$OUT/lxsvc" && mkdir -p "$OUT/lxsvc"
  (cd "$OUT/lxsvc" && unzip -qo "$DL/lxsvc/classes.jar")
  LIBXP_INPUT=$(rg --files "$OUT/lxsvc" -g '*.class')
fi

# NanoHTTPD 同样必须作为输入：只放 classpath 的话 R8 不会把它打进 dex，
# 运行时 NoClassDefFoundError: fi/iki/elonen/NanoHTTPD。
# NanoHTTPD 必须作为输入（只放 classpath R8 不会打包）
NANO_INPUT=""
if [ -f "$DL/nanohttpd.jar" ]; then
  rm -rf "$OUT/nano" && mkdir -p "$OUT/nano"
  (cd "$OUT/nano" && unzip -qo "$DL/nanohttpd.jar")
  NANO_INPUT=$(rg --files "$OUT/nano" -g '*.class')
fi

java -Xmx2g -XX:TieredStopAtLevel=1 -cp "$DL/r8.jar" com.android.tools.r8.R8 --min-api 26 \
  --lib "$DL/android-all.jar" $EXTRA_CP $ANN_CP \
  --output "$OUT/dex" \
  --pg-conf tools/keep.r8 \
  $(rg --files "$OUT/classes" -g '*.class') \
  $LIBXP_INPUT \
  $NANO_INPUT \
  "$KSTDLIB" > "$OUT/r8.log" 2>&1 || {
    rg -i 'error|exception|caused by' "$OUT/r8.log" | head -20 >&2
    echo "!! R8 执行失败" >&2
    exit 1
  }

if [ ! -f "$OUT/dex/classes.dex" ]; then
  echo "!! R8 未产出 classes.dex" >&2
  exit 1
fi

echo "== 5. manifest (axml), minversion=$MINVER"
python3 tools/axml.py "$OUT/AndroidManifest.xml" "$MINVER"
python3 tools/arsc.py "$OUT/resources.arsc"

echo "== 6. entry ($MODE)"
if [ "$MODE" = "new" ]; then
  echo "$ENTRY" > "$OUT/META-INF/xposed/java_init.list"
  printf 'minApiVersion=102\ntargetApiVersion=102\nstaticScope=true\nautoHotReload=true\n' \
    > "$OUT/META-INF/xposed/module.prop"
  printf 'com.taobao.idlefish\n' > "$OUT/META-INF/xposed/scope.list"
else
  echo "$ENTRY" > "$OUT/assets/xposed_init"
fi

echo "== 7. package"
cd "$OUT"
cp dex/classes.dex classes.dex
rm -f u.apk
if [ "$MODE" = "new" ]; then
  python3 ../../tools/pack.py u.apk \
    AndroidManifest.xml=AndroidManifest.xml \
    classes.dex=classes.dex \
    resources.arsc=resources.arsc \
    res/drawable-nodpi/ic_launcher.png=../../app/src/main/res/drawable-nodpi/ic_launcher.png \
    META-INF/xposed/java_init.list=META-INF/xposed/java_init.list \
    META-INF/xposed/module.prop=META-INF/xposed/module.prop \
    META-INF/xposed/scope.list=META-INF/xposed/scope.list
else
  python3 ../../tools/pack.py u.apk \
    AndroidManifest.xml=AndroidManifest.xml \
    classes.dex=classes.dex \
    resources.arsc=resources.arsc \
    res/drawable-nodpi/ic_launcher.png=../../app/src/main/res/drawable-nodpi/ic_launcher.png \
    assets/xposed_init=assets/xposed_init
fi
cd "$ROOT"

echo "== 8. sign"
rm -rf "$OUT/signed"
python3 tools/sign.py "$OUT/u.apk" "$OUT/signed"
# 产物名用应用名，不要再叫 xy-sidecar（那是早期代号，装到手机上对不上号）
cp "$OUT"/signed/*.apk "$OUT/U闲.apk"
java -jar "$DL/signer.jar" -a "$OUT/U闲.apk" -y --skipZipAlign > "$OUT/verify.log" 2>&1
ls -l "$OUT/U闲.apk"
echo 'BUILD SUCCESSFUL'
