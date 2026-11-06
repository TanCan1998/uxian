#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
KLIB=${KLIB:-/tmp/kc24/kotlinc/lib}
DL=${DL:-/tmp/dl}
OUT=$(mktemp -d /tmp/uxian-capture-test.XXXXXX)
trap 'rm -rf "$OUT"' EXIT
java -Xmx1g -XX:TieredStopAtLevel=1 -cp "$KLIB/*" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -nowarn -jvm-target 17 -no-reflect \
  -classpath "$DL/android-all.jar:$DL/lxaar/classes.jar" \
  app/src/main/kotlin/io/github/tancan1998/uxian/TempCapture.kt tools/temp-capture-tests/*.kt -d "$OUT"
java -cp "$OUT:$KLIB/kotlin-stdlib.jar:$DL/android-all.jar:$DL/lxaar/classes.jar" io.github.tancan1998.uxian.TestKt
