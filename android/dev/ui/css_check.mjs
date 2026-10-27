// Structural checks for the Android skin (css/mobile.css).
//
// 1. the stylesheet parses with zero CSS syntax errors (css-tree, strict mode)
// 2. every rule is scoped to `html.android` — that is the guarantee that the
//    desktop UI is untouched by the mobile skin
// 3. the frontend really is loaded with the skin attached (class + link tag)
// 4. the desktop stylesheets and scripts stay free of Android special-casing
//
// (The "does every selector match a live element" check runs against the booted
// app in ui_test.mjs, where the JS-injected nodes — nav, cards, job rows, sheets
// — actually exist.)
//
// Usage: node dev/ui/css_check.mjs [path-to-index.html]
import { readFileSync } from "node:fs";
import * as csstree from "css-tree";

const ROOT = new URL("../..", import.meta.url).pathname.replace(/\/$/, "");
const WEB = `${ROOT}/app/src/main/assets/web`;
const CSS_PATH = `${WEB}/css/mobile.css`;
const INDEX = process.argv[2] || `${WEB}/index.html`;

let passed = 0;
const failures = [];
const check = (cond, what) => {
  if (cond) { passed++; console.log(`  ok  ${what}`); }
  else { failures.push(what); console.log(`  FAIL ${what}`); }
};

// ── 1. parse ────────────────────────────────────────────────────────────────
const css = readFileSync(CSS_PATH, "utf8");
const errors = [];
const ast = csstree.parse(css, {
  positions: true,
  onParseError: (e) => errors.push(`${e.message} @ line ${e.line}`),
});
check(errors.length === 0, `stylesheet parses cleanly${errors.length ? ": " + errors.join("; ") : ""}`);

// ── 2. scoping ──────────────────────────────────────────────────────────────
const rules = [];
csstree.walk(ast, function (node) {
  // `from`/`to`/`50%` steps inside @keyframes are Rules too — skip them
  if (node.type === "Rule" && !this.atrule && node.prelude.type === "SelectorList") {
    rules.push({
      line: node.loc?.start.line ?? 0,
      selectors: csstree.generate(node.prelude).split(",").map((s) => s.trim()),
    });
  }
});
check(rules.length > 100, `stylesheet carries ${rules.length} scoped rules`);
const unscoped = rules.flatMap((r) => r.selectors.filter((s) => !s.startsWith("html.android")));
check(unscoped.length === 0, `all ${rules.length} rules are scoped to html.android${unscoped.length ? " — leaks: " + [...new Set(unscoped)].slice(0, 6).join(" | ") : ""}`);

// ── 3. the page opts into the skin ──────────────────────────────────────────
const html = readFileSync(INDEX, "utf8");
check(html.includes('class="android"'), "index.html is marked as the Android shell");
check(html.includes('/static/css/mobile.css'), "index.html loads the mobile skin last");
check(html.indexOf("mobile.css") > html.indexOf("views.css"), "the skin wins the cascade");
check(html.includes("viewport-fit=cover"), "viewport opts into safe-area insets");
check(html.includes('name="theme-color"'), "system bar is tinted to the app background");

// ── 3b. mobile layout invariants ────────────────────────────────────────────
// Values are read straight out of the stylesheet AST (var() kept symbolic), so
// these assertions fail loudly if the bar moves or a touch target shrinks.
const decls = new Map();   // selector -> { prop: value }
csstree.walk(ast, function (node) {
  if (node.type === "Rule" && !this.atrule && node.prelude.type === "SelectorList") {
    for (const sel of csstree.generate(node.prelude).split(",").map((x) => x.trim())) {
      const bag = decls.get(sel) || {};
      if (node.block && node.block.children) {
        node.block.children.forEach((d) => {
          if (d.type === "Declaration") bag[d.property] = csstree.generate(d.value).trim();
        });
      }
      decls.set(sel, bag);
    }
  }
});
const val = (sel, prop) => (decls.get(sel) || {})[prop] || "";
const varNum = (name) => {
  const m = String(val("html.android", name) || "").match(/(\d+(?:\.\d+)?)px/);
  return m ? Number(m[1]) : NaN;
};

check(/^fixed$/.test(val("html.android .sidebar", "position")), "tab bar is fixed to the viewport");
check(/^0$/.test(val("html.android .sidebar", "bottom")), "tab bar hugs the bottom edge");
check(/left:\s*0/.test(css.split("html.android .sidebar {")[1]?.slice(0, 400) || ""), "tab bar spans the full width");
check(val("html.android .viewport", "padding-bottom").includes("--nav-h"), "content is padded clear of the tab bar");
check(val("html.android .side-foot", "position") === "fixed" && val("html.android .side-foot", "bottom").includes("--nav-h"),
  "speed/about/exit float above the tab bar");
check(val("html.android .toast-stack", "bottom").includes("--nav-h"), "toasts sit above the tab bar");
check(varNum("--nav-h") >= 56, `tab bar is a real tap surface (${varNum("--nav-h")}px)`);
check(varNum("--tap") >= 44, `primary controls meet the 44px touch minimum (--tap: ${varNum("--tap")}px)`);
check(val("html.android .btn", "height") === "var(--tap)", "buttons use the touch height token");
check(/flex-direction:\s*column|flex-direction: column/.test(val("html.android .paste-bar", "flex-wrap")) || val("html.android .paste-bar", "flex-wrap") === "wrap",
  "url field wraps above its actions on a phone");
check(val("html.android .ac-actions", "flex-direction") === "column", "result actions stack vertically");
check(val("html.android .modal-root", "align-items") === "end", "dialogs are bottom sheets");
check(val("html.android .dl-actions", "opacity") === "1", "row actions never hide behind hover");
check(val("html.android .sidebar", "backdrop-filter") === "none",
  "tab bar is not a containing block (keeps the floating cluster anchored)");
check(/html\.android \.modal \{[^}]*width: 100%/.test(css.replace(/\s+/g, " ")),
  "the bottom-sheet rule stands on its own, without :has()");

// ── 4. the desktop stylesheet set is untouched ──────────────────────────────
for (const sheet of ["base.css", "components.css", "views.css"]) {
  const desktop = readFileSync(`${WEB}/css/${sheet}`, "utf8");
  check(!desktop.includes("html.android"), `${sheet} stays desktop-only`);
}
// behaviour must be untouched: no logic branches on being on Android
const JS = ["api.js", "app.js", "ui.js", "state.js", "icons.js",
            "views/direct.js", "views/downloads.js", "views/settings.js"];
const logic = JS.map((f) => [f, readFileSync(`${WEB}/js/${f}`, "utf8")]);
const leaks = logic.filter(([, src]) =>
  /html\.android|classList\.(add|toggle)\(["']android|__AVD_ANDROID__|isAndroid|mobile\.css/.test(src));
check(leaks.length === 0, `frontend JS never branches on Android${leaks.length ? " — " + leaks.map(([f]) => f).join(", ") : ""}`);

console.log();
if (failures.length === 0) {
  console.log(`ALL ${passed} SKIN CHECKS PASSED`);
  process.exit(0);
}
console.log(`${passed} passed, ${failures.length} FAILED`);
failures.forEach((f) => console.log(`  - ${f}`));
process.exit(1);
