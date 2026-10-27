#!/usr/bin/env bash
# Compiles core/ + api/ (+ this harness) for a plain desktop JVM and runs the
# fixtures. Those packages contain no android.* imports, so this executes the
# same source that ships in the APK.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(dirname "$HERE")"
TOOLS="${AVD_TOOLS:-/tmp}"
KOTLIN_HOME="${KOTLIN_HOME:-$TOOLS/kc17/package}"
JAVA_HOME="${JAVA_HOME:-$TOOLS/tc/jdkvenv/lib/python3.11/site-packages/jdk4py/java-runtime}"
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"

OUT="$ROOT/build/jvm-tests"
rm -rf "$OUT" && mkdir -p "$OUT"

mapfile -t SRC < <(find "$ROOT/app/src/main/java/com/allvideodownloader/avd/core" \
                        "$ROOT/app/src/main/java/com/allvideodownloader/avd/api" \
                        "$HERE" -name '*.kt' | sort)

echo "==> compiling ${#SRC[@]} files for the desktop JVM"
java -Xmx2g -cp "$KOTLIN_HOME/lib/kotlin-compiler.jar" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 1.8 -nowarn -no-reflect \
    -cp "$KOTLIN_HOME/lib/kotlin-stdlib.jar" \
    -d "$OUT" "${SRC[@]}"

echo "==> running engine fixtures"
java -Davd.assets="$ROOT/app/src/main/assets/web" \
     -cp "$OUT:$KOTLIN_HOME/lib/kotlin-stdlib.jar" dev.JvmTestsKt

echo
echo "==> running page-scan fixtures (player config → qualities → download)"
java -cp "$OUT:$KOTLIN_HOME/lib/kotlin-stdlib.jar" dev.PageTestsKt

echo
echo "==> checking the Android skin (parse + scoping + desktop untouched)"
if [ -d "$HERE/ui/node_modules" ]; then
    node "$HERE/ui/css_check.mjs"
else
    echo "    skipped: run 'npm install' in android/dev/ui to enable this suite"
fi

echo
echo "==> running the real frontend against the real API (jsdom)"
if [ -d "$HERE/ui/node_modules" ]; then
    KOTLIN_STDLIB="$KOTLIN_HOME/lib/kotlin-stdlib.jar" node "$HERE/ui/ui_test.mjs"
else
    echo "    skipped: run 'npm install' in android/dev/ui to enable this suite"
fi

echo
echo "==> running end-to-end download tests"
java -cp "$OUT:$KOTLIN_HOME/lib/kotlin-stdlib.jar" dev.DownloadTestsKt
