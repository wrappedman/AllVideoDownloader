#!/usr/bin/env bash
# The APK ships the *unchanged* desktop frontend plus the Android-only skin.
#   web/                      → app/src/main/assets/web  (verbatim copy)
#   android/mobile/mobile.css → that copy's css/        (scoped via html.android)
#   index.html                → +class="android" +mobile.css link (nothing else)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec python3 "$HERE/apply_mobile_ui.py"
