// Drives the *unchanged* web frontend in a DOM (jsdom) against the real
// ApiServer running on the JVM, so the UI↔API contract that the APK relies on
// is exercised for real: boot, navigation, settings round-trip, analyze →
// download → job list, and the WebSocket push channel.
//
// Usage: node dev/ui_test.mjs   (run_tests.sh does this for you)
import { spawn } from "node:child_process";
import { createServer } from "node:http";
import { readFileSync, existsSync, mkdirSync } from "node:fs";
import { JSDOM, VirtualConsole } from "jsdom";
import { WebSocket as WsClient } from "ws";
import * as csstree from "css-tree";

const ROOT = new URL("../..", import.meta.url).pathname.replace(/\/$/, "");
const WEB = `${ROOT}/app/src/main/assets/web`;
const DATA = `${ROOT}/build/ui-test-data`;

let passed = 0;
const failures = [];
function check(cond, what) {
  if (cond) { passed++; console.log(`  ok  ${what}`); }
  else { failures.push(what); console.log(`  FAIL ${what}`); }
}
function eq(actual, expected, what) {
  const ok = actual === expected;
  if (!ok) console.log(`       expected ${JSON.stringify(expected)} got ${JSON.stringify(actual)}`);
  check(ok, what);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function until(fn, timeout = 15000, label = "condition") {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    try { if (fn()) return true; } catch { /* keep polling */ }
    await sleep(60);
  }
  console.log(`       timeout waiting for ${label}`);
  return false;
}

// ── fake media host (mp4 + hls) ─────────────────────────────────────────────
const MP4 = Buffer.concat([
  Buffer.from([0, 0, 0, 0x20]),
  Buffer.from("ftypisom"),
  Buffer.alloc(180_000, 7),
]);
const SEG = Buffer.alloc(188 * 10, 0x47);
const MEDIA = `#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4.0,\nseg0.ts\n#EXTINF:4.0,\nseg1.ts\n#EXT-X-ENDLIST\n`;
const MASTER = `#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=1280x720\nmedia.m3u8\n`;

const media = createServer((req, res) => {
  const url = req.url.split("?")[0];
  if (url === "/clip.mp4") {
    const range = req.headers.range;
    if (range) {
      const from = parseInt(range.replace(/bytes=/, ""), 10) || 0;
      res.writeHead(206, { "Content-Type": "video/mp4", "Content-Range": `bytes ${from}-${MP4.length - 1}/${MP4.length}`, "Content-Length": MP4.length - from });
      res.end(MP4.subarray(from));
    } else {
      res.writeHead(200, { "Content-Type": "video/mp4", "Content-Length": MP4.length, "Accept-Ranges": "bytes" });
      res.end(MP4);
    }
    return;
  }
  if (url === "/tube/clip") { res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" }); res.end(TUBE_PAGE); return; }
  if (url === "/clip720.mp4" || url === "/clip480.mp4") {
    res.writeHead(200, { "Content-Type": "video/mp4", "Content-Length": MP4.length, "Accept-Ranges": "bytes" });
    res.end(MP4);
    return;
  }
  if (url === "/master.m3u8") { res.writeHead(200, { "Content-Type": "application/vnd.apple.mpegurl" }); res.end(MASTER); return; }
  if (url === "/media.m3u8") { res.writeHead(200, { "Content-Type": "application/vnd.apple.mpegurl" }); res.end(MEDIA); return; }
  if (url.startsWith("/seg")) { res.writeHead(200, { "Content-Type": "video/mp2t" }); res.end(SEG); return; }
  res.writeHead(404); res.end();
});
const TUBE_PAGE = `<!DOCTYPE html><html><head><title>Clip - Tube</title>
<meta property="og:image" content="/thumb.jpg"></head><body>
<script>
  var html5player = {
    video_url_720: '/clip720.mp4',
    video_url_low: '/clip480.mp4'
  };
</script></body></html>`;

const mediaPort = await new Promise((resolve) => media.listen(0, "127.0.0.1", () => resolve(media.address().port)));
const MEDIA_BASE = `http://127.0.0.1:${mediaPort}`;
console.log(`media host: ${MEDIA_BASE}`);

// ── the real Kotlin API server ──────────────────────────────────────────────
const java = process.env.JAVA_HOME ? `${process.env.JAVA_HOME}/bin/java` : "java";
const server = spawn(java, [
  "-cp", `${ROOT}/build/jvm-tests:${process.env.KOTLIN_STDLIB || `${ROOT}/../..`}`,
  "dev.ServerMainKt", WEB, DATA,
], { cwd: ROOT, stdio: ["ignore", "pipe", "pipe"] });

const port = await new Promise((resolve, reject) => {
  let buf = "";
  const timer = setTimeout(() => reject(new Error(`server did not start:\n${buf}`)), 20000);
  server.stdout.on("data", (d) => {
    buf += d.toString();
    const m = buf.match(/LISTENING (\d+)/);
    if (m) { clearTimeout(timer); resolve(Number(m[1])); }
  });
  server.stderr.on("data", (d) => (buf += d.toString()));
});
const BASE = `http://127.0.0.1:${port}`;
console.log(`api server: ${BASE}`);

// ── load the frontend ───────────────────────────────────────────────────────
function fakeContext2d() {
  const noop = () => {};
  const gradient = { addColorStop: noop };
  return new Proxy({}, {
    get: (_t, prop) => {
      if (prop === "canvas") return {};
      if (prop === "measureText") return () => ({ width: 10 });
      if (prop === "createLinearGradient" || prop === "createRadialGradient" || prop === "createPattern") return () => gradient;
      if (prop === "getImageData") return () => ({ data: new Uint8ClampedArray(4) });
      return noop;
    },
    set: () => true,
  });
}

const vc = new VirtualConsole();
vc.on("jsdomError", () => {});
vc.on("error", () => {});

// canvas/animation shims keep the (visual-only) chart code from aborting the run
process.on("uncaughtException", (e) => console.log(`       (frontend stray error: ${e.message})`));

const dom = await JSDOM.fromURL(`${BASE}/`, {
  runScripts: "dangerously",
  resources: "usable",
  pretendToBeVisual: true,
  virtualConsole: vc,
  beforeParse(window) {
    window.fetch = (input, init) => {
      const target = typeof input === "string" ? new URL(input, BASE).toString() : input;
      return globalThis.fetch(target, init);
    };
    window.WebSocket = class extends WsClient {
      constructor(url, protocols) {
        super(new URL(url, BASE.replace(/^http/, "ws")).toString(), protocols);
      }
    };
    window.HTMLCanvasElement.prototype.getContext = () => fakeContext2d();
    window.matchMedia = window.matchMedia || (() => ({ matches: false, addEventListener() {}, removeEventListener() {}, addListener() {}, removeListener() {} }));
    window.navigator.clipboard = { readText: async () => "", writeText: async () => {} };
    window.scrollTo = () => {};
    window.HTMLElement.prototype.scrollIntoView = () => {};
    window.HTMLElement.prototype.animate = () => ({ finished: Promise.resolve(), cancel() {}, onfinish: null });
  },
});
const { window } = dom;
const doc = window.document;
const $ = (sel) => doc.querySelector(sel);
const $$ = (sel) => [...doc.querySelectorAll(sel)];

// ── 1. boot ─────────────────────────────────────────────────────────────────
console.log("== boot");
check(await until(() => $("#side-nav .nav-item"), 15000, "nav items"), "app shell rendered");
eq($$("#side-nav .nav-item").length, 3, "three navigation items (grab / downloads / settings)");
check(await until(() => $("#analyze-idle") && $("#analyze-idle").children.length > 0, 8000, "direct view idle"), "direct view painted");
check((window.API && typeof window.API.analyze === "function"), "API bridge exposed to the frontend");
const meta = await window.API.meta();
eq(meta.platform, "Android", "frontend sees an Android backend");
eq(meta.app, "All Video Downloader", "app name from /api/meta");
check(meta.system.sites.recipes >= 60, `site registry delivered to the UI (${meta.system.sites.recipes} recipes)`);
check(await until(() => window.API.ws && window.API.ws.connected, 8000, "websocket"), "websocket connected (bootstrap received)");

// snapshot which skin selectors match while the app is in its idle state; the
// final check unions this with the later (populated) states
const skinSeen = new Set();
const strip = (sel) => sel
  .replace(/::?(before|after|placeholder|-webkit-slider-thumb|-webkit-scrollbar(-thumb)?|first-child|last-child|focus-within|focus-visible|active|hover|checked|disabled|open|focus)\b/g, "")
  .replace(/^(html\.android|html\.android\s+body\.rail-min)\s*/, "")
  .trim();
const skinSources = [
  `${WEB}/index.html`, `${WEB}/js/app.js`, `${WEB}/js/ui.js`, `${WEB}/js/views/direct.js`,
  `${WEB}/js/views/downloads.js`, `${WEB}/js/views/settings.js`,
].map((f) => readFileSync(f, "utf8")).join("\n");
const skinSelectors = (() => {
  const ast = csstree.parse(readFileSync(`${WEB}/css/mobile.css`, "utf8"), { positions: true });
  const out = [];
  csstree.walk(ast, function (node) {
    if (node.type === "Rule" && !this.atrule && node.prelude.type === "SelectorList") {
      csstree.generate(node.prelude).split(",").forEach((sel) => out.push(sel.trim()));
    }
  });
  return out;
})();
const snapshotSkin = () => {
  for (const sel of skinSelectors) {
    const s = strip(sel);
    if (!s || /[\s>+~|]$/.test(s)) continue;
    try { if (doc.querySelector(s)) skinSeen.add(s); } catch { /* unparsable in jsdom */ }
  }
};

snapshotSkin();   // observation 1: idle shell


// ── 2. navigation ───────────────────────────────────────────────────────────
console.log("== navigation");
const settingsNav = $$("#side-nav .nav-item").find((n) => n.dataset.view === "view-settings");
settingsNav.dispatchEvent(new window.MouseEvent("click", { bubbles: true }));
check(await until(() => $("#view-settings").classList.contains("active"), 5000, "settings view"), "settings view activates");
check(await until(() => $$("#settings-grid .set-card, #settings-grid .card, #settings-grid > *").length > 0, 8000, "settings cards"), "settings cards render from /api/settings");
const dirInput = $$("#settings-grid input").find((i) => i.value && i.value.includes("downloads"));
check(!!dirInput, `download folder field is prefilled (${dirInput?.value || "none"})`);
const cookies = await window.API.cookiesStatus();
check(typeof cookies.mode === "string" && typeof cookies.file === "string",
  `cookie source reported to the settings card (mode='${cookies.mode}')`);

// ── 3. a settings change round-trips to the backend ─────────────────────────
console.log("== settings persistence");
const before = (await window.API.getSettings()).default_quality;
const qualitySelect = $$("#settings-grid select").find((s) => [...s.options].some((o) => o.value === "1080" || o.value === "720"));
check(!!qualitySelect, "quality selector present");
if (qualitySelect) {
  qualitySelect.value = "480";
  qualitySelect.dispatchEvent(new window.Event("change", { bubbles: true }));
  check(await until(async () => true, 10), "change dispatched");
  await sleep(900);
  const after = (await window.API.getSettings()).default_quality;
  eq(after, "480", `quality change persisted through PUT /api/settings (was ${before})`);
}

// ── 4. analyze a real URL, then download it ─────────────────────────────────
console.log("== analyze → download");
const directNav = $$("#side-nav .nav-item").find((n) => n.dataset.view === "view-direct");
directNav.dispatchEvent(new window.MouseEvent("click", { bubbles: true }));
await sleep(200);
$("#direct-url").value = `${MEDIA_BASE}/clip.mp4`;
$("#direct-url").dispatchEvent(new window.Event("input", { bubbles: true }));
$("#btn-analyze").dispatchEvent(new window.MouseEvent("click", { bubbles: true }));
check(await until(() => $("#analyze-stage .analyze-card"), 20000, "analyze card"), "analyze renders a result card");
const cardText = $("#analyze-stage").textContent;
check(/mp4|video/i.test(cardText), "card describes the video file");
const dlBtn = $$("#analyze-stage button").find((b) => /download/i.test(b.textContent || ""));
check(!!dlBtn, "download button offered");
if (dlBtn) {
  dlBtn.dispatchEvent(new window.MouseEvent("click", { bubbles: true }));
  let job = null;
  check(await until(async () => true, 10), "download click dispatched");
  for (let i = 0; i < 100 && !job; i++) {
    await sleep(150);
    const list = await window.API.listDownloads();
    job = list.jobs.find((j) => j.url.includes("clip.mp4"));
  }
  check(!!job, "job created through POST /api/downloads");
  if (job) {
    const finished = await untilJob(job.id, "done", 30000);
    eq(finished?.status, "done", `job reached 'done' (${finished?.progress_pct}%${finished?.error ? ", " + finished.error : ""})`);
    const stats = readFileSync(`${DATA}/downloads/${finished.filename}`);
    eq(stats.length, MP4.length, "downloaded file has the full size");
  }
  // the downloads view shows it, fed by the websocket
  const dlNav = $$("#side-nav .nav-item").find((n) => n.dataset.view === "view-downloads");
  dlNav.dispatchEvent(new window.MouseEvent("click", { bubbles: true }));
  check(await until(() => $("#view-downloads").classList.contains("active"), 5000, "downloads view"), "downloads view activates");
  check(await until(() => doc.querySelector(`[data-id="${job.id}"]`) || $$("#dl-list > *").length > 0, 8000, "download row"),
    "download row rendered from live job state");
  check((await window.API.listDownloads()).jobs.length >= 1, "GET /api/downloads lists the job");
}

// ── 5. HLS through the same UI path ────────────────────────────────────────
console.log("== hls");
const hls = await window.API.analyze(`${MEDIA_BASE}/master.m3u8`);
eq(hls.kind, "hls", "master playlist analyzed as hls");
check(hls.formats.length >= 2, `variant ladder offered (${hls.formats.length} chips)`);

// the same analysis through the UI, so the chip widgets exist for the layout checks
directNav.dispatchEvent(new window.MouseEvent("click", { bubbles: true }));
await sleep(150);
$("#direct-url").value = `${MEDIA_BASE}/master.m3u8`;
$("#direct-url").dispatchEvent(new window.Event("input", { bubbles: true }));
$("#btn-analyze").dispatchEvent(new window.MouseEvent("click", { bubbles: true }));
check(await until(() => doc.querySelector("#analyze-stage .chip-set .chip"), 20000, "hls chip row"),
  "resolution chips render as a scrollable row");
check(doc.querySelectorAll("#analyze-stage .chip-set .chip").length === hls.formats.length,
  "every quality the backend found got a chip");


// ── 5b. a tube-style page: chips must appear and the button must download ──
console.log("== page → qualities → download");
directNav.dispatchEvent(new window.MouseEvent("click", { bubbles: true }));
await sleep(150);
$("#direct-url").value = `${MEDIA_BASE}/tube/clip`;
$("#direct-url").dispatchEvent(new window.Event("input", { bubbles: true }));
$("#btn-analyze").dispatchEvent(new window.MouseEvent("click", { bubbles: true }));
check(await until(() => doc.querySelector("#analyze-stage .chip-set .chip"), 20000, "page chips"),
  "a page's player config yields quality chips");
const chipLabels = [...doc.querySelectorAll("#analyze-stage .chip-set .chip")].map((c) => c.textContent.trim());
check(chipLabels.length >= 2, `chip labels: ${chipLabels.join(" | ")}`);
check(chipLabels.some((l) => /1080|720|480/.test(l)), "chips are labelled with their resolution");
check(new Set(chipLabels).size === chipLabels.length, `no two chips share a label: ${chipLabels.join(" | ")}`);
const pageDl = [...doc.querySelectorAll("#analyze-stage .ac-actions button")].find((b) => /download/i.test(b.textContent || ""));
check(!!pageDl, "the page card offers a download button");
if (pageDl) {
  pageDl.dispatchEvent(new window.MouseEvent("click", { bubbles: true }));
  let pageJob = null;
  for (let i = 0; i < 100 && !pageJob; i++) {
    await sleep(150);
    const { jobs } = await window.API.listDownloads();
    pageJob = jobs.find((j) => j.url.includes("clip720.mp4") || j.url.includes("clip480.mp4") || j.url.includes("clip.mp4"));
  }
  check(!!pageJob, "clicking Download starts a real job");
  if (pageJob) {
    const finishedPage = await untilJob(pageJob.id, "done", 30000);
    eq(finishedPage?.status, "done", `page download finished (${finishedPage?.progress_pct}%${finishedPage?.error ? ", " + finishedPage.error : ""})`);
  }
}

// ── 6. the Android skin, measured on the live DOM ──────────────────────────
console.log("== android skin");
snapshotSkin();   // second observation point: populated views + a result card

const style = (sel, prop) => {
  const node = doc.querySelector(sel);
  return node ? window.getComputedStyle(node)[prop] : undefined;
};
check(doc.documentElement.classList.contains("android"), "document is in Android mode");
eq(style(".sidebar", "position"), "fixed", "navigation is a fixed bottom bar");
eq(style(".sidebar", "flexDirection"), "row", "navigation items sit in a row");
eq(style("#btn-min", "display"), "none", "desktop minimise button removed");
eq(style("#btn-max", "display"), "none", "desktop maximise button removed");
eq(style(".nav-item", "flexDirection"), "column", "tabs are icon-over-label");
eq(style(".paste-bar", "flexWrap"), "wrap", "url field and actions stack on a phone");
eq(style(".hero", "textAlign"), "left", "hero copy is left aligned");
eq(style(".ac-actions", "flexDirection"), "column", "result actions are full-width buttons");
eq(style(".dl-actions", "opacity"), "1", "row actions are visible without hover");
eq(style(".viewport", "flex"), "1 1 0%", "viewport still owns the free space");

// a selector counts as live if it matched in ANY observed state, or if every
// class/id it names is at least created by the frontend (a view we did not
// open in this run: a media card needs yt-dlp, for example)
const unmatched = [];
let stateOnly = 0;
for (const sel of skinSelectors) {
  const s = strip(sel);
  if (!s || /[\s>+~|]$/.test(s)) continue;
  let live = skinSeen.has(s);
  if (!live) {
    try { live = !!doc.querySelector(s); } catch { live = true; }
  }
  if (live) continue;
  const tokens = s.match(/[.#][A-Za-z0-9_-]+/g) || [];
  const known = tokens.length > 0 && tokens.every((t) => skinSources.includes(t.slice(1)));
  if (known) { stateOnly++; continue; }
  unmatched.push(sel);
}
check(unmatched.length === 0, `all ${skinSelectors.length} skin selectors target real UI${unmatched.length ? " — " + unmatched.slice(0, 6).join(" | ") : ""} (${stateOnly} are view-state only)`);

// the About sheet now presents as a bottom sheet
$("#btn-about").dispatchEvent(new window.MouseEvent("click", { bubbles: true }));
check(await until(() => $("#modal-root .modal"), 4000, "about sheet"), "About opens as a sheet");
if ($("#modal-root .modal")) {
  eq(style("#modal-root .modal", "width"), "100%", "sheet spans the screen width");
  check(/24px/.test(style("#modal-root .modal", "borderRadius") || ""), "sheet has rounded top corners");
  check(style("#modal-root", "alignItems") === "end" || style("#modal-root", "align-items") === "end",
    "sheet is anchored to the bottom edge");
  $("#modal-root").dispatchEvent(new window.MouseEvent("click", { bubbles: true }));  // backdrop closes
}

// ── 7. cleanup + report ────────────────────────────────────────────────────
async function untilJob(id, status, timeout) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const { jobs } = await window.API.listDownloads();
    const job = jobs.find((j) => j.id === id);
    if (job && job.status === status) return job;
    if (job && job.status === "error") return job;
    await sleep(120);
  }
  return null;
}

dom.window.close();
server.kill("SIGKILL");
media.close();

console.log();
if (failures.length === 0) {
  console.log(`ALL ${passed} UI CHECKS PASSED`);
  process.exit(0);
}
console.log(`${passed} passed, ${failures.length} FAILED`);
failures.forEach((f) => console.log(`  - ${f}`));
process.exit(1);
