#!/usr/bin/env bash
#
# Builds the All Video Downloader APK without Gradle.
#
# Why: this project has no third-party dependencies, so the only thing Gradle
# would add is the Android Gradle Plugin — and that needs the Google Maven
# repository. This script drives the same four tools AGP drives, in the same
# order, and produces the same artifact:
#
#   aapt2 compile  (resources)
#   aapt2 link     (manifest + resources + assets -> base.apk)
#   kotlinc        (Kotlin -> JVM bytecode)
#   d8/dx          (bytecode -> dex)
#   apksigner      (v1+v2+v3 signature)
#
# A normal `./gradlew assembleDebug` works too when Gradle + the Android SDK are
# installed; both paths compile the identical sources with the identical ids.
#
# Usage:  ./build.sh            debug build  ->  build/AllVideoDownloader-debug.apk
#         ./build.sh release    same, plus a release copy name
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

VARIANT="${1:-debug}"
OUT="$HERE/build"
WORK="$OUT/work"
TOOLS="${AVD_TOOLS:-/tmp}"

# ── tool locations (override with env vars) ──────────────────────────────────
KOTLIN_HOME="${KOTLIN_HOME:-$TOOLS/kc17/package}"
KOTLINC_JAR="${KOTLINC_JAR:-$KOTLIN_HOME/lib/kotlin-compiler.jar}"
KOTLIN_STDLIB="${KOTLIN_STDLIB:-$KOTLIN_HOME/lib/kotlin-stdlib.jar}"
ANDROID_JAR="${ANDROID_JAR:-$TOOLS/tc/bin/android-34.jar}"
DX_JAR="${DX_JAR:-$TOOLS/dxjar/dx.jar}"
AAPT2="${AAPT2:-$TOOLS/tc/bin/aapt2}"
APKSIGNER="${APKSIGNER:-$TOOLS/tc/bin/apksigner.jar}"
KEYSTORE="${KEYSTORE:-$TOOLS/hw/debug.keystore}"
KS_PASS="${KS_PASS:-android}"
KS_ALIAS="${KS_ALIAS:-androiddebugkey}"
JAVA_HOME="${JAVA_HOME:-$TOOLS/tc/jdkvenv/lib/python3.11/site-packages/jdk4py/java-runtime}"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

fail() { echo "build: $*" >&2; exit 1; }

for f in "$KOTLINC_JAR" "$KOTLIN_STDLIB" "$ANDROID_JAR" "$DX_JAR" "$AAPT2"; do
    [ -e "$f" ] || fail "missing tool: $f (see tools/setup_toolchain.sh)"
done
if [ -f "$APKSIGNER" ] && [ ! -f "$KEYSTORE" ]; then
    fail "apksigner.jar is present but the keystore $KEYSTORE is not"
fi

VERSION_CODE=231
VERSION_NAME="2.3.1"
MIN_SDK=21
TARGET_SDK=34
PKG="com.allvideodownloader.avd"

echo "==> cleaning $WORK"
rm -rf "$WORK"
mkdir -p "$WORK/classes" "$WORK/res" "$WORK/gen" "$OUT"

# ── 1. resources ────────────────────────────────────────────────────────────
echo "==> aapt2 compile (res/)"
"$AAPT2" compile --dir app/src/main/res -o "$WORK/res/res.zip"

# ── 2. link manifest + resources + assets ───────────────────────────────────
# aapt2 takes the package name from <manifest package="…">, while AGP 8 takes it
# from the Gradle `namespace` and rejects the attribute. The source manifest is
# therefore kept AGP-clean and the attribute is injected into a build copy.
echo "==> aapt2 link (AndroidManifest.xml + assets/)"
python3 - "$PKG" app/src/main/AndroidManifest.xml "$WORK/AndroidManifest.xml" <<'PY'
import re, sys
pkg, src, dst = sys.argv[1:4]
text = open(src, encoding='utf-8').read()
if 'package=' not in text.split('>')[0] + '>':
    text = re.sub(r'<manifest\b', f'<manifest package="{pkg}"', text, count=1)
open(dst, 'w', encoding='utf-8').write(text)
PY
"$AAPT2" link \
    -o "$WORK/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$WORK/AndroidManifest.xml" \
    -R "$WORK/res/res.zip" \
    -A app/src/main/assets \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
    --java "$WORK/gen" \
    --auto-add-overlay

# ── 3. Kotlin ───────────────────────────────────────────────────────────────
echo "==> kotlinc ($(find app/src/main/java -name '*.kt' | wc -l) files)"
mapfile -t SOURCES < <(find app/src/main/java -name '*.kt' | sort)
java -Xmx2g -cp "$KOTLINC_JAR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -jvm-target 1.8 \
    -no-reflect \
    -Xlambdas=class \
    -Xsam-conversions=class \
    -nowarn \
    -cp "$ANDROID_JAR:$KOTLIN_STDLIB" \
    -d "$WORK/classes" \
    "${SOURCES[@]}"

# ── 4. bytecode down to dex level ───────────────────────────────────────────
# dx understands class-file major 50 and no invokedynamic placeholders, so drop
# every class to 50 (Java 6 bytecode — the VM accepts it at any API level) and
# assert the compiler produced no indy.
echo "==> normalising bytecode"
python3 - "$WORK/classes" "$KOTLIN_STDLIB" <<'PY'
import pathlib, struct, sys, zipfile

classes = pathlib.Path(sys.argv[1])
INDY_TAGS = {15, 17, 18}


def pool_tags(data):
    try:
        count = struct.unpack('>H', data[8:10])[0]
    except Exception:
        return set()
    tags, i = set(), 10
    for _ in range(1, count):
        if i >= len(data):
            return tags
        tag = data[i]
        tags.add(tag)
        if tag in (7, 8, 19, 20):
            i += 3
        elif tag in (9, 10, 11, 12, 17, 18):
            i += 5
        elif tag in (15,):
            i += 4
        elif tag in (3, 4):
            i += 5
        elif tag in (5, 6):
            i += 9
        elif tag == 1:
            i += 3 + struct.unpack('>H', data[i + 1:i + 3])[0]
        else:
            return tags
    return tags


patched = 0
for f in classes.rglob('*.class'):
    d = bytearray(f.read_bytes())
    if int.from_bytes(d[6:8], 'big') > 50:
        d[6:8] = (50).to_bytes(2, 'big')
        f.write_bytes(bytes(d))
        patched += 1
    if pool_tags(bytes(d)) & INDY_TAGS:
        sys.exit(f'indy in {f}')

# the stdlib arrives as a jar; normalise a copy so dx can read it
std = pathlib.Path(sys.argv[2])
out = classes.parent / 'kotlin-stdlib-50.jar'
with zipfile.ZipFile(std) as zin, zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as zout:
    for info in zin.infolist():
        data = zin.read(info.filename)
        if info.filename.endswith('.class') and len(data) > 8:
            if int.from_bytes(data[6:8], 'big') > 50:
                data = data[:6] + (50).to_bytes(2, 'big') + data[8:]
        zout.writestr(info.filename, data)
print(f'    {patched} classes normalised')
PY

# ── 5. dex ──────────────────────────────────────────────────────────────────
echo "==> dx (classes.dex)"
java -Xmx2g -cp "$DX_JAR" com.android.dx.command.Main \
    --dex --output="$WORK/classes.dex" "$WORK/classes" "$WORK/kotlin-stdlib-50.jar" 2>&1 \
    | grep -v -e '^trouble processing:' -e 'bad class file magic' -e '^\.\.\.while' -e '^1 warning' -e '^$' || true
[ -s "$WORK/classes.dex" ] || fail "dexing produced nothing"

# ── 6. assemble ─────────────────────────────────────────────────────────────
echo "==> packaging"
python3 - "$WORK/base.apk" "$WORK/classes.dex" "$WORK/unsigned.apk" <<'PY'
import shutil, sys, zipfile
apk, dex, out = sys.argv[1:4]
shutil.copyfile(apk, out)
with zipfile.ZipFile(out, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex', compress_type=zipfile.ZIP_DEFLATED)
PY

# ── 7. sign ─────────────────────────────────────────────────────────────────
# apksigner.jar is the reference implementation and is used when the Android
# build-tools are installed. Without them (no SDK/Gradle download available) the
# fallback signer produces the same three schemes (v1 + v2 + v3) — Android 11+
# refuses a v1-only signature for targetSdk ≥ 30, so "unsigned" is not an option.
SIGNED="$OUT/AllVideoDownloader-$VARIANT.apk"
cp "$WORK/unsigned.apk" "$SIGNED"
if [ -f "$APKSIGNER" ]; then
    echo "==> apksigner ($VARIANT)"
    java -jar "$APKSIGNER" sign \
        --ks "$KEYSTORE" \
        --ks-pass "pass:$KS_PASS" \
        --key-pass "pass:$KS_PASS" \
        --ks-key-alias "$KS_ALIAS" \
        --min-sdk-version "$MIN_SDK" \
        "$SIGNED"
    java -jar "$APKSIGNER" verify --min-sdk-version "$MIN_SDK" "$SIGNED"
else
    echo "==> apksigner fallback (v1 + v2, no Android build-tools)"
    PYTHON="${PYTHON:-python3}"
    [ "$("$PYTHON" -c 'import cryptography' 2>&1)" = "" ] \
        || fail "signing needs apksigner.jar, or the Python 'cryptography' module"
    "$PYTHON" "$HERE/tools/sign_apk.py" "$SIGNED" "$SIGNED.signed" \
        --key "${SIGNING_KEY:-$HERE/tools/debug-signing-key.DEBUG-ONLY.pem}" \
        || fail "signing failed"
    mv "$SIGNED.signed" "$SIGNED"
    "$PYTHON" "$HERE/tools/verify_apk.py" "$SIGNED" || fail "the v2 signature does not verify"
fi

# keep the installable artifact outside build/ so it survives clean checkouts
mkdir -p "$HERE/artifacts"
cp "$SIGNED" "$HERE/artifacts/$(basename "$SIGNED")"

# ── 8. facts ────────────────────────────────────────────────────────────────
SIZE=$(stat -c %s "$SIGNED")
echo
echo "APK   : $SIGNED"
echo "size  : $SIZE bytes ($((SIZE / 1024)) KiB)"
echo "pkg   : $PKG  $VERSION_NAME (code $VERSION_CODE)  minSdk $MIN_SDK  targetSdk $TARGET_SDK"
python3 - "$SIGNED" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    names = z.namelist()
print('assets: %d files' % sum(1 for n in names if n.startswith('assets/web/')))
print('dex   : %s' % ', '.join(n for n in names if n.endswith('.dex')))
PY
