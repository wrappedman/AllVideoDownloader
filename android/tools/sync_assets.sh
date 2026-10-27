#!/usr/bin/env bash
# The APK ships the *unchanged* web frontend. This refreshes the copy in
# app/src/main/assets/web from the repo root after any UI change.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
DEST="$ROOT/android/app/src/main/assets/web"
rm -rf "$DEST"
mkdir -p "$DEST"
cp -R "$ROOT/web/." "$DEST/"
echo "synced $(find "$DEST" -type f | wc -l) files from web/ → android/app/src/main/assets/web"
