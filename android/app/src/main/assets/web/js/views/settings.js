/* All Video Downloader · views/settings.js
 *
 * One page, four cards, two balanced columns:
 *
 *   Download folder · speed     │  container · quality · cookies
 *   accent · ambient            │  runtime health · update
 *
 * Everything is persisted server-side to ~/.avd/settings.json; every control
 * saves on change (debounced) and the header flashes "Saved".
 */
(function () {
  const { $, $$, el, toast } = window.UI;
  const S = window.Store;

  /* one debounced writer for the whole page — a control change is 1 request */
  let pending = null;
  let flushTimer = null;
  function save(patch, { immediate = false } = {}) {
    pending = { ...(pending || {}), ...patch };
    clearTimeout(flushTimer);
    flushTimer = setTimeout(flush, immediate ? 0 : 300);
  }
  async function flush() {
    const patch = pending;
    pending = null;
    if (!patch) return;
    try {
      const merged = await window.API.saveSettings(patch);
      S.settings = merged;
      S.emit("settings.changed", merged);
      flashSaved();
    } catch (e) {
      toast(`Couldn't save: ${e.message}`, "err");
    }
  }

  let flashTimer = null;
  function flashSaved() {
    const chip = $("#set-saved");
    if (!chip) return;
    chip.classList.add("on");
    clearTimeout(flashTimer);
    flashTimer = setTimeout(() => chip.classList.remove("on"), 1400);
  }

  /* ── page ──────────────────────────────────────────────────────────────── */
  function init() {
    const grid = $("#settings-grid");
    if (!grid) return;
    grid.innerHTML = "";
    const path = $("#cfg-path");
    if (path) path.textContent = S.meta?.config_file || "~/.avd/settings.json";

    // row-major, so the page reads left→right: what you download first, how it
    // is captured second, then looks, then runtime health.
    grid.append(secDownloads(), secMedia(), secAppearance(), secSystem());
  }

  function card(title, sub, iconName, ...fields) {
    return el("section", { class: "card set-card" },
      el("header", { class: "sc-head" },
        el("span", { class: "sc-ico", html: ICONS.svg(iconName) }),
        el("div", {},
          el("h3", {}, title),
          sub ? el("p", { class: "sc-sub" }, sub) : null)),
      el("div", { class: "sc-body" }, ...fields));
  }

  const field = (label, ...controls) =>
    el("div", { class: "field" }, el("label", { class: "f-label" }, label), ...controls);
  const hint = (text) => el("p", { class: "f-hint" }, text);
  /* The long explanations used to be four-line paragraphs inside every card —
   * that is what made this page look like a wall of text. One line stays
   * visible; the rest is one click away and costs no height. */
  const more = (label, text) => {
    const d = el("details", { class: "f-more" });
    d.append(el("summary", {}, label), el("p", {}, text));
    return d;
  };
  /* two short controls share one row instead of stacking into a long column */
  const pair = (...kids) => el("div", { class: "set-pair" }, ...kids);

  /* ── download folder, speed, network ───────────────────────────────────── */
  function secDownloads() {
    const st = S.settings;

    const dirInput = el("input", {
      class: "input", value: st.download_dir || "", spellcheck: "false",
      onchange: (e) => save({ download_dir: e.target.value.trim() }, { immediate: true }),
    });
    const browse = el("button", {
      class: "btn btn-ghost btn-sm", type: "button", title: "Choose a folder",
      html: ICONS.svg("folder"),
      onclick: async () => {
        const picked = await window.API.bridge.pickFolder?.();
        if (picked) {
          dirInput.value = picked;
          save({ download_dir: picked }, { immediate: true });
        } else {
          toast("The folder picker needs the desktop app — in browser mode edit the path directly.", "info");
        }
      },
    });

    const maxRange = el("input", {
      type: "range", min: 1, max: 8, value: st.max_concurrent,
      style: { "--v": `${((st.max_concurrent - 1) / 7) * 100}%` },
    });
    const maxVal = el("span", { class: "range-val" }, `${st.max_concurrent} parallel`);
    maxRange.addEventListener("input", () => {
      maxRange.style.setProperty("--v", `${((maxRange.value - 1) / 7) * 100}%`);
      maxVal.textContent = `${maxRange.value} parallel`;
      save({ max_concurrent: +maxRange.value });
    });

    return card("Downloads", "Where files land and how hard the engine pulls.", "download",
      field("Download folder", el("div", { class: "input-row" }, dirInput, browse)),
      field("Concurrency", el("div", { class: "range-line" }, maxRange, maxVal)),
      field("Network proxy (optional)",
        el("input", {
          class: "input", placeholder: "http://127.0.0.1:7890 · socks5://user:pass@host:port",
          value: st.proxy || "", spellcheck: "false",
          onchange: (e) => save({ proxy: e.target.value.trim() }, { immediate: true }),
        }),
        hint("Routes every request — downloads, stream capture and yt-dlp — through the proxy."),
        more("When do I need this?",
          "A CDN that geoblocks your network, or an ISP that throttles video. Supports http:// and socks5:// " +
          "(with user:pass). The FFmpeg child process is proxied too, so manifest capture follows the same route.")),
      setRow("Simulate on network failure",
        "If the host is unreachable, finish the job in simulation so a dead link never looks like a crash.",
        switchOpt("simulate_on_network_error", st.simulate_on_network_error)));
  }

  /* ── container, quality, cookies, ffmpeg ───────────────────────────────── */
  function secMedia() {
    const st = S.settings;
    const sys = S.meta.system || {};
    const ff = sys.ffmpeg || {};

    const caps = el("div", { class: "cap-row" },
      el("span", { class: `cap ${ff.available ? "ok" : "bad"}`, html: `<span class="dot"></span>FFmpeg ${ff.available ? "detected" : "not found"}` }),
      el("span", { class: `cap ${sys.yt_dlp ? "ok" : "bad"}`, html: `<span class="dot"></span>yt-dlp ${sys.yt_dlp || "not installed"}` }),
      el("span", { class: `cap ${sys.tls_impersonation ? "ok" : "warn"}`, html: `<span class="dot"></span>TLS fingerprint ${sys.tls_impersonation ? "on" : "off"}` }));

    const containerSel = selectOpt(["mp4", "mkv", "webm", "mov"],
      { mp4: "MP4 · best compatibility", mkv: "MKV · max flexibility", webm: "WebM", mov: "MOV" },
      st.default_container, (v) => save({ default_container: v }, { immediate: true }));
    const qualitySel = selectOpt(["best", "2160", "1440", "1080", "720", "480"],
      { best: "Best available", 2160: "2160p (4K)", 1440: "1440p", 1080: "1080p (FHD)", 720: "720p", 480: "480p" },
      String(st.default_quality), (v) => save({ default_quality: v }, { immediate: true }));

    const ffPath = el("input", {
      class: "input", placeholder: "C:\\Tools\\ffmpeg\\bin", value: st.ffmpeg_path || "",
      onchange: (e) => save({ ffmpeg_path: e.target.value.trim() }, { immediate: true }),
    });
    const recheck = el("button", {
      class: "btn btn-ghost btn-sm", type: "button", onclick: async (e) => {
        const b = e.currentTarget;
        b.disabled = true;
        try {
          const merged = await window.API.saveSettings({ ffmpeg_path: ffPath.value.trim() });
          S.settings = merged;
          S.meta = await window.API.meta();
          init();
          toast(S.meta.system.ffmpeg.available
            ? "FFmpeg detected — streams will capture losslessly."
            : "Still no FFmpeg at that path.", S.meta.system.ffmpeg.available ? "ok" : "warn");
        } catch (err) {
          toast(err.message, "err");
        } finally {
          b.disabled = false;
        }
      },
    }, "Re-check");

    return card("Video & capture", "How streams are extracted, merged and remuxed.", "film",
      caps,
      pair(
        field("Preferred container", containerSel),
        field("Default quality", qualitySel)),
      field("Browser cookies source",
        selectOpt(["", "chrome", "edge", "brave", "chromium", "firefox", "vivaldi"],
          { "": "None (anonymous)", chrome: "Chrome", edge: "Edge", brave: "Brave", chromium: "Chromium", firefox: "Firefox", vivaldi: "Vivaldi" },
          st.ytdlp_browser_cookies || "", (v) => save({ ytdlp_browser_cookies: v }, { immediate: true })),
        hint("Your session cookies, read locally (never uploaded) — beats bot walls and age gates."),
        more("Which browser should I pick?",
          "Chrome keeps its cookie database locked while it runs, and Chrome 127+ on Windows App-Bound-encrypts " +
          "it, so reading it can fail with “Could not copy Chrome cookie database”. Firefox never locks its store " +
          "and is the reliable choice. Leave this on None and every extraction simply retries without cookies.")),
      field("Cookies file (cookies.txt, optional)",
        el("div", { class: "input-row" }, el("input", {
          class: "input", placeholder: "C:\\Users\\you\\cookies.txt", value: st.cookies_file || "",
          spellcheck: "false",
          onchange: (e) => save({ cookies_file: e.target.value.trim() }, { immediate: true }),
        }), el("button", { class: "btn btn-ghost btn-sm", type: "button", onclick: checkCookies }, "Check cookies")),
        hint("Beats the browser source every time — the one option that always reads."),
        more("Why a file?",
          "Chrome's database is locked while the browser runs (yt-dlp issue 7271), so “Could not copy Chrome " +
          "cookie database” is a setup problem, not a site problem. Export the file with a “Get cookies.txt " +
          "LOCALLY” style extension while you are logged in; the app needs no access to your browser at all.")),
      field("FFmpeg folder (optional)",
        el("div", { class: "input-row" }, ffPath, recheck),
        hint("Needed for .m3u8/.mpd capture and for merging separate audio + video."),
        more("How do I install it?",
          "Windows: winget install Gyan.FFmpeg (or download the official build and point this field at its bin " +
          "folder), then press Re-check. The app also offers to fetch it automatically on first launch when it " +
          "is missing.")));
  }

  /* ── appearance ────────────────────────────────────────────────────────── */
  function secAppearance() {
    const st = S.settings;
    const ACCENTS = {
      violet: ["#a855f7", "#4f7cff"], azure: ["#38bdf8", "#4f7cff"],
      magenta: ["#f472b6", "#a855f7"], mint: ["#34d399", "#38bdf8"],
    };
    const sw = el("div", { class: "swatches" });
    for (const [name, [c1, c2]] of Object.entries(ACCENTS)) {
      const b = el("button", {
        class: `swatch${st.accent === name ? " on" : ""}`, type: "button", title: name,
        style: { background: `linear-gradient(120deg, ${c1}, ${c2})`, "--sw-c": c1 },
      });
      b.addEventListener("click", () => {
        $$(".swatch", sw).forEach((x) => x.classList.remove("on"));
        b.classList.add("on");
        document.documentElement.dataset.accent = name;
        save({ accent: name }, { immediate: true });
      });
      sw.append(b);
    }
    return card("Appearance", "How the app looks while you wait for the bar to fill.", "sparkle",
      field("Accent glow", sw),
      setRow("Ambient effects", "Drifting light orbs and film grain behind the glass.",
        switchOpt("ambient_effects", st.ambient_effects)));
  }

  /* ── runtime health ────────────────────────────────────────────────────── */
  function secSystem() {
    const m = S.meta;
    const sys = m.system || {};
    const engine = sys.yt_dlp_engine || {};

    const upd = el("button", {
      class: "btn btn-ghost btn-sm", type: "button", onclick: async (e) => {
        const b = e.currentTarget;
        const orig = b.innerHTML;
        b.disabled = true;
        b.innerHTML = `<span class="spinner"></span> pip update…`;
        try {
          const r = await window.API.selfupdate();
          if (r.ok) toast(`yt-dlp → ${r.version}. Restart the app to load it.`, "ok");
          else toast(`pip failed: ${(r.detail || "").slice(-160)}`, "err", { ttl: 9000 });
        } catch (err) {
          toast(err.message, "err");
        } finally {
          b.disabled = false;
          b.innerHTML = orig;
        }
      },
    }, "Update now");

    const kv = (k, v) => el("div", { class: "kv" }, el("span", { class: "k" }, k), el("span", { class: "v" }, v));

    return card("System", "Runtime health and the tools this app is driving.", "cpu",
      el("div", { class: "set-row" },
        el("div", { class: "sr-txt" },
          el("b", {}, `yt-dlp extractor · ${sys.yt_dlp || "not installed"}`),
          el("span", {}, engine.use_cli
            ? "Driving the newer yt-dlp found on your PATH — terminal parity by construction."
            : "YouTube changes its API weekly; a stale copy is why the app offers fewer qualities.")),
        upd),
      el("div", { class: "kv-list" },
        kv("App", `${m.app} ${m.version}`),
        kv("Shell", sys.desktop ? "pywebview · frameless · WebView2" : "browser dev mode"),
        kv("Backend", `Python ${m.python} · FastAPI`),
        kv("Platform", m.platform),
        kv("Site recipes", `${sys.sites?.recipes || 0} sites · player-config engine`),
        kv("TLS impersonation", sys.tls_impersonation
          ? "on · curl_cffi (browser fingerprint for bot walls)"
          : "off · pip install curl_cffi to beat Cloudflare 403s"),
        kv("Settings file", el("code", {}, m.config_file))));
  }

  /* ── shared widgets ────────────────────────────────────────────────────── */
  function setRow(title, sub, control) {
    return el("div", { class: "set-row" },
      el("div", { class: "sr-txt" }, el("b", {}, title), el("span", {}, sub)), control);
  }

  function switchOpt(key, checked, onChange) {
    const input = el("input", { type: "checkbox" });
    input.checked = !!checked;
    input.addEventListener("change", () => {
      onChange ? onChange(input.checked) : save({ [key]: input.checked }, { immediate: true });
    });
    return el("label", { class: "switch-line" }, input, el("span", { class: "switch" }));
  }

  function selectOpt(values, labels, selected, onChange) {
    const sel = el("select", { class: "input" });
    for (const v of values) {
      sel.append(el("option", {
        value: v,
        ...(String(v) === String(selected) ? { selected: "" } : {}),
      }, labels?.[v] ?? v));
    }
    sel.addEventListener("change", () => onChange(sel.value));
    return sel;
  }

  /* Reads the configured cookie source locally (no network) and reports exactly
   * what breaks — "Could not copy Chrome cookie database" finally has a button. */
  async function checkCookies() {
    toast("Reading the cookie source…", "info", { icon: "clock", ttl: 2500 });
    try {
      const r = await window.API.cookiesProbe();
      const opts = { icon: r.ok ? "check" : "warning", ttl: r.ok ? 5000 : 16000 };
      if (r.suggest) {
        opts.actionLabel = `Use ${r.suggest}`;
        opts.onAction = () => useBestCookies();
      }
      toast(r.message, r.ok ? "ok" : "warn", opts);
      S.meta = await window.API.meta();
    } catch (e) {
      toast(String(e?.message || e), "err");
    }
  }

  /* One click instead of "go export a cookies.txt": pick the browser on this
     machine whose cookie database actually reads (usually Firefox). */
  async function useBestCookies() {
    try {
      const r = await window.API.cookiesUseBest();
      toast(r.message, r.ok ? "ok" : "warn", { icon: r.ok ? "check" : "warning", ttl: r.ok ? 6000 : 12000 });
      if (r.ok) {
        const merged = await window.API.saveSettings({ ytdlp_browser_cookies: r.browser });
        S.settings = merged;
        S.emit("settings.changed", merged);
        init();
      }
      S.meta = await window.API.meta();
    } catch (e) {
      toast(String(e?.message || e), "err");
    }
  }

  window.SettingsView = { init, flush };
})();
