#!/usr/bin/env python3
"""Lay the Android presentation layer over the bundled frontend copy.

`web/` is the desktop UI and stays untouched. The Android build ships a copy of
it (app/src/main/assets/web) with exactly three additions, all presentation:

  1. <html class="android">            — scopes every mobile rule
  2. <link href="/static/css/mobile.css">  — the touch layout itself
  3. viewport `viewport-fit=cover` + theme colour  — safe-area + system-bar tint

No script, id, class or DOM node used by web/js/*.js is modified, so behaviour
is identical to the desktop build.

Run directly, or through tools/sync_assets.sh after changing web/.
"""
from __future__ import annotations

import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "web"
MOBILE_CSS = ROOT / "android" / "mobile" / "mobile.css"
DEST = ROOT / "android" / "app" / "src" / "main" / "assets" / "web"

INDEX_MARK = "<!-- android-mobile-ui -->"


def sync_web_copy() -> int:
    if DEST.exists():
        shutil.rmtree(DEST)
    shutil.copytree(SRC, DEST)
    return sum(1 for _ in DEST.rglob("*") if _.is_file())


def install_skin() -> None:
    target = DEST / "css" / "mobile.css"
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(MOBILE_CSS, target)


def patch_index() -> None:
    path = DEST / "index.html"
    html = path.read_text(encoding="utf-8")
    if INDEX_MARK in html:
        return
    original = html

    # 1. scope + 2. viewport with safe-area support + theme colour
    html = html.replace(
        '<html lang="en">',
        f'<html lang="en" class="android">\n{INDEX_MARK}',
        1,
    )
    html = html.replace(
        '<meta name="viewport" content="width=device-width, initial-scale=1.0" />',
        '<meta name="viewport" content="width=device-width, initial-scale=1.0, '
        'viewport-fit=cover" />\n'
        '<meta name="theme-color" content="#0c0c10" />\n'
        '<meta name="color-scheme" content="dark" />',
        1,
    )
    # 3. the skin, last so it wins the cascade
    html = html.replace(
        '<link rel="stylesheet" href="/static/css/views.css" />',
        '<link rel="stylesheet" href="/static/css/views.css" />\n'
        '<link rel="stylesheet" href="/static/css/mobile.css" />',
        1,
    )
    if html == original:  # pragma: no cover - guards against a reshaped index.html
        sys.exit("android ui: could not patch index.html (unexpected markup)")

    path.write_text(html, encoding="utf-8")


def main() -> None:
    files = sync_web_copy()
    install_skin()
    patch_index()
    print(f"android ui: {files} frontend files + mobile.css → {DEST.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
