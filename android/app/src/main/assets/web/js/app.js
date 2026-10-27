/* All Video Downloader · app.js — bootstrap, window chrome, navigation shell */
(function () {
  const { $, $$, el, toast } = window.UI;
  const S = window.Store;

  const NAV = [
    { view: "view-direct", label: "Grab a video", icon: "film", key: "1" },
    { view: "view-downloads", label: "Downloads", icon: "download", key: "2", badge: true },
    { view: "view-settings", label: "Settings", icon: "settings", key: "3" },
  ];

  /* ── boot ─────────────────────────────────────────────────────────────── */
  async function boot() {
    $("#brand-logo").innerHTML = ICONS.svg("download")
      .replace('stroke-width="1.9"', 'stroke-width="2.4" style="width:20px;height:20px;color:#fff"');
    buildTitlebar();
    buildSidebar();
    wireWindow();

    try {
      const [meta, settings] = await Promise.all([window.API.meta(), window.API.getSettings()]);
      S.meta = meta; S.settings = settings;
      window.__WEBFINDER_DESKTOP__ = !!meta.system.desktop;
      applySettings();
      netStatus(true);
      S.emit("ready");
      window.API.ws.connect();
    } catch (e) {
      netStatus(false);
      toast("Backend unreachable — is `run.bat` running?", "err", { ttl: 8000 });
      setTimeout(boot, 2500);
      return;
    }

    window.DirectView.init();
    window.DownloadsView.init();
    window.SettingsView.init();

    S.on("settings.changed", applySettings);
    S.on("nav", (v) => showView(v));
    requestAnimationFrame(() => document.body.classList.remove("preload"));

    addEventListener("offline", () => netStatus(false));
    addEventListener("online", () => netStatus(true));
  }

  function applySettings() {
    const st = S.settings;
    document.documentElement.dataset.accent = st.accent || "violet";
    document.documentElement.classList.toggle("no-ambient", !st.ambient_effects);
    $("#cfg-path").textContent = S.meta?.config_file || "";
  }

  /* ── titlebar (custom window frame) ───────────────────────────────────── */
  function buildTitlebar() {
    $("#btn-min").innerHTML = ICONS.svg("minus");
    $("#btn-max").innerHTML = ICONS.svg("square");
    $("#btn-close").innerHTML = ICONS.svg("x");
    const bridge = window.API.bridge;
    $("#btn-min").onclick = () => runDesktop(bridge.minimize);
    const setMaxIcon = (maxed) => { $("#btn-max").innerHTML = ICONS.svg(maxed ? "restore" : "square"); $("#btn-max").dataset.maxed = maxed ? "1" : ""; };
    $("#btn-max").onclick = async (e) => {
      const btn = e.currentTarget;   // capture pre-await: currentTarget clears after dispatch
      const next = await runDesktop(bridge.toggleMaximize);
      setMaxIcon(typeof next === "boolean" ? next : !btn.dataset.maxed);
      btn.animate([{ transform: "scale(.8)" }, { transform: "scale(1)" }], { duration: 180, easing: "cubic-bezier(.34,1.56,.64,1)" });
    };
    // double-click on the (draggable) titlebar = maximize/restore, like native windows
    document.querySelector(".titlebar").addEventListener("dblclick", async (e) => {
      if (!bridge.isDesktop || e.target.closest(".no-drag")) return;
      const next = await runDesktop(bridge.toggleMaximize);
      setMaxIcon(typeof next === "boolean" ? next : !$("#btn-max").dataset.maxed);
    });
    $("#btn-close").onclick = () => exitApp();
    $("#app-version").textContent = `v${S.meta?.version || "2.2"}`;
    document.title = "All Video Downloader";
  }
  async function runDesktop(fn) { try { return await fn?.(); } catch { /* browser mode */ } }

  /* ── exit (sidebar button + window ✕ share this path) ─────────────────── */
  let exiting = false;
  async function exitApp() {
    if (exiting) return;
    exiting = true;
    const overlay = el("div", { id: "exit-overlay" },
      el("div", { class: "exit-card" },
        el("div", { class: "exit-ico", html: ICONS.svg("download") }),
        el("b", {}, "All Video Downloader is closing"),
        el("p", {}, "Downloads in flight are saved up to the last chunk — resume them next launch.")));
    document.body.append(overlay);
    try { await window.API.exit(); } catch { /* server already gone */ }
    if (window.API.bridge.isDesktop) runDesktop(window.API.bridge.close);
    setTimeout(() => { try { window.close(); } catch { /* tabs refuse scripted close */ } }, 600);
  }

  function wireWindow() {
    // in the sandbox/browser preview there is no frameless chrome to emulate drag
    document.querySelector(".titlebar").addEventListener("pointerdown", (e) => {
      if (!window.API.bridge.isDesktop) return;
      if (e.target.closest(".no-drag")) return;
    });
  }

  /* ── sidebar ────────────────────────────────────────────────────────────── */
  function buildSidebar() {
    const nav = $("#side-nav");
    nav.innerHTML = "";
    NAV.forEach((item, i) => {
      const b = el("button", { class: "nav-item" + (i === 0 ? " active" : ""), "data-view": item.view, title: `${item.label}  ·  Ctrl+${item.key}` },
        el("span", { html: ICONS.svg(item.icon) }),
        el("span", { class: "nav-label" }, item.label),
        item.badge ? el("span", { class: "nav-count hidden", id: "nav-dl-count" }) : null);
      b.addEventListener("click", () => showView(item.view));
      b.addEventListener("mouseenter", () => slideIndicator(b));
      b.addEventListener("mouseleave", () => {
        const a = nav.querySelector(".nav-item.active"); if (a) slideIndicator(a);
      });
      nav.append(b);
    });
    nav.addEventListener("pointerleave", () => {
      const a = nav.querySelector(".nav-item.active"); if (a) slideIndicator(a);
    });
    $("#btn-about").innerHTML = `${ICONS.svg("info")}<span>About</span>`;
    $("#btn-about").addEventListener("click", showAbout);
    $("#btn-exit").innerHTML = `${ICONS.svg("exit")}<span>Exit</span>`;
    $("#btn-exit").addEventListener("click", exitApp);
    addEventListener("keydown", (e) => {
      if ((e.ctrlKey || e.metaKey) && /^[1-9]$/.test(e.key) && NAV[+e.key - 1]) {
        e.preventDefault();
        showView(NAV[+e.key - 1].view);
      }
    });
    setTimeout(() => { const a = nav.querySelector(".active"); if (a) slideIndicator(a); }, 30);
  }

  /* ── About sheet (graphical, with author card) ──────────────────────────── */
  function showAbout() {
    const chip = (t) => el("span", { class: "about-chip" }, t);
    const feat = (icon, title, text) => el("div", { class: "about-feat" },
      el("span", { class: "af-ico", html: ICONS.svg(icon) }),
      el("div", {}, el("b", {}, title), el("p", {}, text)));
    const openExt = (u) => {
      try { const w = window.open(u, "_blank", "noopener"); if (!w) throw new Error("blocked"); }
      catch { window.UI.copyText(u); toast("Link copied to the clipboard.", "info"); }
    };
    const sys = S.meta?.system || {};
    const body = el("div", {},
      el("div", { class: "about-hero" },
        el("div", { class: "ah-ico", html: ICONS.svg("film") }),
        el("div", {},
          el("h2", {}, "All Video Downloader", el("i", {}, `v${S.meta?.version || "2.2"}`)),
          el("p", {}, "One job, done properly: every link that contains a video — any site, any container, any stream — becomes a file on your disk at the quality YOU picked."))),
      el("div", { class: "about-feats" },
        feat("zap", "Universal link engine", "yt-dlp's ~1,900 extractors, raw .mp4/.mkv files, HLS/DASH manifests, and pages that hide their video — deep-scanned and re-analyzed until a real stream is found."),
        feat("film", "Real quality choice", "Resolution chips carry exact yt-dlp format IDs (`137+140/…`); for raw manifests the variant ladder is parsed locally so you pick the rung — never a lottery."),
        feat("shield", "YouTube, without the drama", "Multi-client extraction (android/tv → ios → web), your browser session cookies on demand, and a daily automatic yt-dlp refresh — the anti-bot treadmill runs under the hood."),
        feat("download", "Resumable by design", "HTTP Range resume, pause/retry, concurrent fragment HLS capture via FFmpeg with `-c copy` (no re-encode, no quality loss)."),
        feat("cpu", "Native desktop shell", "pywebview window: frameless, HW-accelerated, native folder picker, auto-installed FFmpeg. Or run browser.bat.")),
      el("div", { class: "about-chips" },
        chip(`yt-dlp ${sys.yt_dlp || "—"}`), chip(sys.ffmpeg?.available ? "FFmpeg ✓" : "FFmpeg auto-install"),
        chip("httpx Range-resume"), chip("FastAPI"), chip("pywebview"), chip("WebView2")),
      el("div", { class: "about-author" },
        el("div", { class: "aa-ava" }, "BE"),
        el("div", { style: { flex: "1" } },
          el("b", {}, "Behnam Ehsani"),
          el("p", {}, "Design · code · relentless edge-case hunting")),
        el("button", { class: "btn btn-ghost btn-sm", onclick: () => openExt("mailto:inbox.ehsani@gmail.com") }, "Email"),
        el("button", { class: "btn btn-ghost btn-sm", onclick: () => openExt("https://github.com/wrappedman") }, "GitHub")));
    window.UI.modal({ title: "About All Video Downloader", body, actions: [{ label: "Close", primary: true }] });
  }

  function slideIndicator(target) {
    const nav = $("#side-nav");
    nav.style.setProperty("--nav-y", `${target.offsetTop}px`);
  }

  function showView(viewId) {
    const views = $$(".view");
    const current = views.find((v) => v.classList.contains("active"));
    if (current?.id === viewId) return;
    if (current) current.classList.remove("active");
    const next = document.getElementById(viewId);
    next.style.animation = "";          // re-arm the view-in transition every visit
    next.classList.add("active");
    $$(".nav-item").forEach((n) => n.classList.toggle("active", n.dataset.view === viewId));
    const active = document.querySelector(".nav-item.active");
    if (active) slideIndicator(active);
    document.getElementById("viewport").scrollTop = 0;
  }

  /* ── global badges / status ─────────────────────────────────────────────── */
  function syncRail() {
    const badge = $("#nav-dl-count");
    if (!badge) return;
    const n = S.activeCount;
    badge.textContent = n;
    badge.classList.toggle("hidden", !n);
  }
  S.on("job.update", syncRail);
  S.on("jobs.hydrated", syncRail);

  function netStatus(online) {
    const n = $("#net-status");
    n.classList.toggle("online", online);
    n.classList.toggle("offline", !online);
    n.querySelector(".net-label").textContent = online ? "connected" : "offline";
  }

  window.App = { boot, showView, syncRail };
  document.readyState === "loading" ? addEventListener("DOMContentLoaded", boot) : boot();
})();
