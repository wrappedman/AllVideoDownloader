/* All Video Downloader · views/direct.js — paste any video link, pick a quality, get the file */
(function () {
  const { $, el, toast, fmtBytes, fmtDuration } = window.UI;
  const S = window.Store;

  const QUICK = [
    { label: "YouTube", url: "https://www.youtube.com/watch?v=dQw4w9WgXcQ" },
    { label: "HLS stream", url: "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8" },
    { label: "Direct MP4", url: "https://filesamples.com/samples/video/movie_1080p.mp4" },
    { label: "DASH stream", url: "https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps.mpd" },
  ];

  let current = null;   // last analysis payload

  function init() {
    $("#pb-icon").innerHTML = ICONS.svg("link");
    $("#btn-analyze .btn-ico").innerHTML = ICONS.svg("scan");
    const ql = $("#quick-links");
    ql.append(el("span", { class: "qlabel" }, "Try"));
    for (const q of QUICK) {
      ql.append(el("button", { class: "qchip", type: "button", onclick: () => { $("#direct-url").value = q.url; analyze(); } }, q.label));
    }
    $("#btn-analyze").addEventListener("click", analyze);
    $("#pb-paste").addEventListener("click", async () => {
      try { $("#direct-url").value = (await navigator.clipboard.readText()).trim(); $("#direct-url").focus(); }
      catch { toast("Clipboard read needs permission — paste with Ctrl+V.", "warn"); }
    });
    $("#direct-url").addEventListener("keydown", (e) => { if (e.key === "Enter") analyze(); });

    const stage = $("#analyze-stage");
    stage.innerHTML = '<div class="analyze-idle" id="analyze-idle"></div>';
    const idle = $("#analyze-idle");
    idle.append(el("div", { class: "ai-inner" },
      el("div", { class: "radar" }, el("div", { class: "ring" }), el("div", { class: "ring" }), el("div", { class: "ring" }),
        el("div", { class: "sweep" }), el("div", { class: "core", html: ICONS.svg("link") })),
      el("p", { style: { marginTop: "22px", fontSize: "13.5px", lineHeight: 1.6 } },
        "Every quality the link exposes becomes a button: platform videos resolve through ", el("b", {}, "yt-dlp"),
        " with real format IDs, ", el("code", {}, ".m3u8"), "/", el("code", {}, ".mpd"),
        " manifests get their variant ladder parsed, raw files stream straight into the manager, and pages are deep-scanned for the video hiding inside them."),
      (S.meta?.sites?.recipes || 0) ? el("p", { style: { marginTop: "10px", fontSize: "12px", color: "var(--text-3)" } },
        `${S.meta.sites.recipes} built-in site recipes (tube sites, Persian, Chinese and mainstream platforms) + universal player-config extraction: a page link is enough.`) : null));
  }

  async function analyze() {
    const url = $("#direct-url").value.trim();
    if (!url) { $("#direct-url").focus(); return; }
    const btn = $("#btn-analyze");
    btn.disabled = true;
    btn.querySelector("span:last-child").innerHTML = `<span class="spinner"></span>`;
    const stage = $("#analyze-stage");
    stage.innerHTML = "";
    stage.append(el("div", { class: "analyze-idle" },
      el("div", {}, el("div", { class: "radar" }, el("div", { class: "ring" }), el("div", { class: "ring" }), el("div", { class: "ring" }),
        el("div", { class: "sweep" }), el("div", { class: "core", html: ICONS.svg("scan") })),
        el("p", { style: { marginTop: 18, fontSize: "13px", color: "var(--text-3)" } },
          "Probing extractors, manifests and content-type…"))));
    try {
      current = await window.API.analyze(url);
      render(current);
    } catch (err) {
      renderError(err.message);
    } finally {
      btn.disabled = false;
      btn.querySelector("span:last-child").textContent = "Grab it";
    }
  }

  /* ── renderers per result kind ─────────────────────────────────────────── */
  function cardShell(kindLabel, color) {
    const stage = $("#analyze-stage");
    stage.innerHTML = "";
    const card = el("div", { class: "card analyze-card" });
    card.style.setProperty("--ft-c", color);
    const head = el("div", { style: { display: "flex", alignItems: "center", gap: 10, marginBottom: "16px", flexWrap: "wrap" } },
      el("span", { class: "rc-type", style: { "--ft-c": color } }, kindLabel),
      el("div", { style: { flex: 1 } }),
      el("button", { class: "icon-btn", title: "Dismiss", html: ICONS.svg("x"), onclick: () => { init(); } }));
    card.append(head);
    stage.append(card);
    return card;
  }

  function render(d) {
    if (d.kind === "media_error") renderMediaError(d);
    else if (d.kind === "media") renderMedia(d);
    else if (d.kind === "hls") renderHls(d);
    else if (d.kind === "webmedia") renderWebMedia(d);
    else if (d.kind === "direct") renderDirect(d);
    else if (d.kind === "magnet") renderMagnet(d);
    else renderPage(d);
    if (d.access_note) {
      const c = document.querySelector("#analyze-stage .analyze-card");
      c?.prepend(el("div", { class: "notice warn", style: { marginBottom: "12px" } },
        el("span", { html: ICONS.svg("warning") }), el("div", {}, d.access_note)));
    }
    if (d.recovered_after_block) {
      const c = document.querySelector("#analyze-stage .analyze-card");
      c?.prepend(el("div", { class: "notice ok", style: { marginBottom: "12px" } },
        el("span", { html: ICONS.svg("info") }),
        el("div", {}, d.recovered_after_block +
          " — the download uses the recovered file, not the blocked page.")));
    }
    if (d.upgraded_from_extractor) {
      const c = document.querySelector("#analyze-stage .analyze-card");
      c?.prepend(el("div", { class: "notice ok", style: { marginBottom: "12px" } },
        el("span", { html: ICONS.svg("info") }), el("div", {}, d.upgraded_from_extractor)));
    }
  }

  /* one list widget for everything ELSE the source exposed: playlist siblings
     and additional files found on the hosting page — completeness is the point */
  function moreBlock(d) {
    const rows = [];
    const ref = d.referer || d.found_on_page || "";
    if (d.playlist?.entries?.length)
      for (const e of d.playlist.entries)
        rows.push({ title: e.title || "untitled", url: e.url, kind: "video", dur: e.duration });
    if (d.also_found?.length)
      for (const c of d.also_found)
        rows.push({ title: c.filename || c.url, url: c.url, kind: "file", size: c.size_bytes });
    if (!rows.length) return null;
    const list = el("div", { class: "rc-candidates", style: { borderStyle: "solid", marginTop: 10 } });
    for (const it of rows.slice(0, 20)) {
      if (!it.url) continue;
      list.append(el("div", { class: "cand" },
        el("span", { class: "cand-name", title: it.url }, it.title
          + (it.dur ? ` · ${fmtDuration(it.dur)}` : "")
          + (it.size ? ` · ${fmtBytes(it.size)}` : "")),
        it.kind === "video"
          ? el("button", { class: "icon-btn", title: "Open this entry in the analyzer", html: ICONS.svg("scan"),
              onclick: () => { $("#direct-url").value = it.url; analyze(); } })
          : el("button", { class: "icon-btn", title: "Analyze this file", html: ICONS.svg("scan"),
              onclick: () => { $("#direct-url").value = it.url; analyze(); } }),
        el("button", { class: "icon-btn", title: "Download directly", html: ICONS.svg("download"),
          onclick: async (e) => {
            const b = e.currentTarget; b.disabled = true;
            try { await window.API.startDownload({ url: it.url, filename: it.title, meta: ref ? { referer: ref } : {} });
              toast("Added to downloads.", "ok", { icon: "dl" }); }
            catch (err) { toast(err.message, "err"); }
            finally { b.disabled = false; }
          } }),
        el("button", { class: "icon-btn", title: "Copy link", html: ICONS.svg("copy"),
          onclick: () => { window.UI.copyText(it.url); toast("Link copied.", "ok", { icon: "copy" }); } })));
    }
    const label = d.playlist?.entries?.length
      ? `More from this playlist · ${d.playlist.total - 1} left`
      : `Also found on this page · ${rows.length}`;
    return el("div", {}, el("div", { class: "ac-block-label", style: { marginTop: 16 } }, label), list);
  }

  function renderMedia(d) {
    const card = cardShell(`MEDIA · ${d.extractor || "yt-dlp"}`, "#a855f7");
    const sel = { height: pickDefaultQuality(d.formats), format: null };
    sel.format = d.formats.find((f) => f.height === sel.height) || d.formats[0];

    const grid = el("div", { class: "ac-grid" });
    const thumb = el("div", { class: "ac-thumb" });
    if (d.thumbnail) { const img = el("img", { alt: "" }); img.onerror = () => img.remove(); img.src = d.thumbnail; thumb.append(img); }
    else thumb.style.background = "linear-gradient(140deg, #241a3d, #0f172a)";
    if (d.duration) thumb.append(el("span", { class: "dur" }, fmtDuration(d.duration)));

    const info = el("div", {});
    info.append(el("h2", { class: "ac-title" }, d.title));
    info.append(el("div", { class: "ac-meta" },
      d.uploader ? el("span", { html: `${ICONS.svg("globe")} ${escapeHtml(d.uploader)}` }) : null,
      d.view_count ? el("span", { html: `${ICONS.svg("zap")} ${d.view_count.toLocaleString()} views` }) : null,
      el("span", { html: `${ICONS.svg("db")} ${d.formats_total} streams found` })));

    if (!d.ffmpeg && !d.demo) {
      info.append(el("div", { class: "notice warn", style: { marginBottom: "12px" },
        html: `${ICONS.svg("warning")}<div>FFmpeg is not installed — highest qualities that need merging will be replaced by the best single-file stream. Install with <code>winget install Gyan.FFmpeg</code> for full power.</div>` }));
    }

    if (d.formats.length && d.formats.every((f) => !f.height || f.height <= 480)) {
      info.append(el("div", { class: "notice warn", style: { marginBottom: "10px" } },
        el("span", { html: ICONS.svg("warning") }),
        el("div", {}, "This host is serving a **capped format map** (nothing above 480p is offered). ",
          "Fix: Settings → Media → Browser cookies source → chrome/edge — a logged-in session unlocks the full ladder. ",
          "The link stays valid; only quality options are limited.")));
    }
    info.append(el("div", { class: "ac-block-label" }, "Resolution"));
    const qrow = el("div", { class: "chip-set", style: { justifyContent: "flex-start" } });
    let dlBtn = null;
    for (const f of d.formats) {
      const active = f === sel.format;
      const chip = el("button", { class: `chip${active ? " on" : ""}`, type: "button", style: { "--chip-c": f.height >= 2160 ? "#f472b6" : f.height ? "#a855f7" : "#38bdf8", height: "34px", padding: "0 13px" } },
        el("span", { html: ICONS.svg(f.height ? "film" : "audio") }),
        f.label + (f.fps ? ` ${Math.round(f.fps)}fps` : ""),
        f.size ? el("span", { style: { opacity: .55, fontSize: "11px" } }, ` ~${fmtBytes(f.size)}`) : null);
      chip.addEventListener("click", () => {
        sel.height = f.height; sel.format = f;
        [...qrow.children].forEach((c) => c.classList.remove("on"));
        chip.classList.add("on");
        if (dlBtn) dlBtn.querySelector("span:last-child").textContent = `Download ${f.label}`;
      });
      qrow.append(chip);
    }
    info.append(qrow);

    const actions = el("div", { class: "ac-actions" },
      dlBtn = el("button", { class: "btn btn-primary", onclick: (e) => enqueue(e, d, sel.format) },
        el("span", { class: "btn-ico", html: ICONS.svg("download") }), el("span", {}, `Download ${sel.format?.label || ""}`.trim())),
      el("button", { class: "btn btn-ghost", onclick: () => { window.UI.copyText(d.url); toast("Media page URL copied.", "ok", { icon: "copy" }); } },
        el("span", { class: "btn-ico", html: ICONS.svg("copy") }), el("span", {}, "Copy source link")),
      el("a", { class: "btn btn-ghost", href: d.webpage || d.url, target: "_blank", rel: "noopener" },
        el("span", { class: "btn-ico", html: ICONS.svg("external") }), el("span", {}, "Open page")));
    info.append(actions);
    const more = moreBlock(d);
    if (more) info.append(more);
    grid.append(thumb, info);
    card.append(grid);
  }

  function pickDefaultQuality(formats) {
    const want = String(window.Store.settings.default_quality || "1080");
    if (want === "best") return formats[0]?.height;
    const hit = formats.find((f) => String(f.height) === want);
    if (hit) return hit.height;
    return (formats.find((f) => f.height) || formats[0] || {}).height;
  }

  /* A page whose own player config lists the files (xvideos, xnxx, xHamster,
     kt_player tubes, jwplayer/video.js pages, __NEXT_DATA__ apps…): every file
     it exposes becomes a chip, and the page rides along as Referer. */
  function renderWebMedia(d) {
    const card = cardShell(`PLAYER · ${d.site || d.player || "embedded"}`, "#22d3ee");
    const grid = el("div", { class: "ac-grid" });
    const thumb = el("div", { class: "ac-thumb" });
    if (d.thumbnail) { const img = el("img", { alt: "" }); img.onerror = () => img.remove(); img.src = d.thumbnail; thumb.append(img); }
    else thumb.style.background = "linear-gradient(140deg, #0b2a33, #0f172a)";

    const info = el("div", {});
    info.append(el("h2", { class: "ac-title" }, d.title || d.url));
    info.append(el("div", { class: "ac-meta" },
      el("span", { html: `${ICONS.svg("film")} ${escapeHtml(d.player || "player")} config` }),
      el("span", { html: `${ICONS.svg("db")} ${d.formats.length} files listed` }),
      d.site ? el("span", { html: `${ICONS.svg("globe")} ${escapeHtml(d.site)}` }) : null));
    info.append(el("div", { class: "notice info", style: { marginBottom: "12px" } },
      el("span", { html: ICONS.svg("info") }),
      el("div", {}, "These files come straight from the page's player — no extractor guesswork. ",
        "The hosting page is sent as ", el("b", {}, "Referer"), " so hotlink-guarded CDNs answer like they do in a browser.")));

    const want = String(S.settings?.default_quality || "1080");
    let sel = d.formats.find((f) => String(f.height) === want) || d.formats[0];
    let dlBtn = null;
    info.append(el("div", { class: "ac-block-label" }, "File the player offers"));
    const qrow = el("div", { class: "chip-set", style: { justifyContent: "flex-start" } });
    for (const f of d.formats) {
      const active = f === sel;
      const label = f.label || (f.ext || "file").toUpperCase();
      const chip = el("button", { class: `chip${active ? " on" : ""}`, type: "button",
        style: { "--chip-c": f.height >= 2160 ? "#f472b6" : f.height ? "#22d3ee" : "#38bdf8", height: "34px", padding: "0 13px" } },
        el("span", { html: ICONS.svg(f.height ? "film" : "link") }), label);
      chip.addEventListener("click", () => {
        sel = f;
        [...qrow.children].forEach((c) => c.classList.remove("on"));
        chip.classList.add("on");
        if (dlBtn) dlBtn.querySelector("span:last-child").textContent = `Download ${label}`;
      });
      qrow.append(chip);
    }
    info.append(qrow);

    const actions = el("div", { class: "ac-actions" },
      dlBtn = el("button", { class: "btn btn-primary", onclick: (e) => downloadFromPlayer(e, d, sel) },
        el("span", { class: "btn-ico", html: ICONS.svg("download") }),
        el("span", {}, `Download ${sel.label || (sel.ext || "file").toUpperCase()}`)),
      el("button", { class: "btn btn-ghost", onclick: () => { window.UI.copyText(sel.url); toast("File link copied.", "ok", { icon: "copy" }); } },
        el("span", { class: "btn-ico", html: ICONS.svg("copy") }), el("span", {}, "Copy file link")),
      el("a", { class: "btn btn-ghost", href: d.url, target: "_blank", rel: "noopener" },
        el("span", { class: "btn-ico", html: ICONS.svg("external") }), el("span", {}, "Open page")));
    info.append(actions);
    const more = moreBlock(d);
    if (more) info.append(more);
    grid.append(thumb, info);
    card.append(grid);
  }

  /* the server picks the worker (http for files, hls/yt-dlp for manifests) —
     we only hand it the exact file the user clicked, plus the page Referer */
  async function downloadFromPlayer(e, d, f) {
    const btn = e.currentTarget;
    const original = btn.innerHTML;
    btn.disabled = true; btn.innerHTML = `<span class="spinner"></span>`;
    try {
      const ext = (f.ext || "mp4").replace(/^\./, "");
      await window.API.startDownload({
        url: f.url,
        title: d.title || "",
        filename: `${(d.title || "video").slice(0, 120)}.${ext}`,
        meta: { referer: d.referer || d.found_on_page || d.url },
      });
      toast("Transfer started — watch it in the Download Manager.", "ok",
        { icon: "dl", actionLabel: "View", onAction: () => S.emit("nav", "view-downloads") });
    } catch (err) { toast(err.message, "err"); }
    finally { btn.disabled = false; btn.innerHTML = original; }
  }

  function renderHls(d) {
    const card = cardShell("STREAM · HLS/DASH" + (d.site ? ` · ${d.site}` : ""), "#38bdf8");
    const body = el("div", {});
    body.append(el("h2", { class: "ac-title", style: { marginBottom: 6 } }, d.title));
    const kv = el("div", {});
    kv.append(el("div", { class: "kv" }, el("span", { class: "k" }, "Manifest"), el("span", { class: "v" }, d.url.slice(0, 90))));
    if (d.duration) kv.append(el("div", { class: "kv" }, el("span", { class: "k" }, "Duration"), el("span", { class: "v" }, fmtDuration(d.duration))));
    if (d.bit_rate) kv.append(el("div", { class: "kv" }, el("span", { class: "k" }, "Bitrate"), el("span", { class: "v" }, `${(d.bit_rate / 1000).toFixed(0)} kbps`)));
    if (d.streams?.length) kv.append(el("div", { class: "kv" }, el("span", { class: "k" }, "Tracks"),
      el("span", { class: "v" }, d.streams.map((x) => `${x.type}:${x.codec}${x.height ? ` ${x.height}p` : ""}`).join(" · "))));
    body.append(kv);

    const laddered = (d.formats || []).length > 1;
    const sel = { fmt: (d.formats || [])[0] || { label: "stream" } };
    if (laddered) {
      body.append(el("div", { class: "ac-block-label", style: { marginTop: 14 } }, "Quality — variants found in the manifest"));
      const qrow = el("div", { class: "chip-set", style: { justifyContent: "flex-start" } });
      let dlBtn = null;
      for (const f of d.formats) {
        const active = f === sel.fmt;
        const chip = el("button", { class: `chip${active ? " on" : ""}`, type: "button",
          style: { "--chip-c": f.height >= 2160 ? "#f472b6" : f.height ? "#a855f7" : "#38bdf8", height: "34px", padding: "0 13px" } },
          el("span", { html: ICONS.svg("film") }), f.label || "variant");
        chip.addEventListener("click", () => {
          sel.fmt = f;
          [...qrow.children].forEach((c) => c.classList.remove("on"));
          chip.classList.add("on");
          if (dlBtn) dlBtn.querySelector("span:last-child").textContent = `Download ${f.label || "chosen variant"}`;
        });
        qrow.append(chip);
      }
      body.append(qrow);
    }
    if (!d.ffmpeg && !laddered) {
      body.append(el("div", { class: "notice warn", style: { marginTop: 14 },
        html: `${ICONS.svg("warning")}<div><b>FFmpeg required.</b> The app installs it automatically on next launch if it is missing — stream capture runs through it.</div>` }));
    }
    body.append(el("div", { class: "ac-actions", style: { marginTop: 14 } },
      el("button", { class: "btn btn-primary", onclick: (e) => enqueueStream(e, d, sel) },
        el("span", { class: "btn-ico", html: ICONS.svg("film") }),
        el("span", {}, laddered ? `Download ${sel.fmt?.label || "chosen variant"}` : "Capture & convert")),
      el("button", { class: "btn btn-ghost", onclick: () => { window.UI.copyText(d.url); toast("Stream URL copied — works with VLC, IDM & yt-dlp too.", "ok", { icon: "copy" }); } },
        el("span", { class: "btn-ico", html: ICONS.svg("copy") }), el("span", {}, "Copy stream URL"))));
    const moreH = moreBlock(d);
    if (moreH) body.append(moreH);
    card.append(body);
  }

  /* the server picks the path: yt-dlp when a specific variant must be muxed
     with its audio track, FFmpeg on the picked variant playlist otherwise */
  async function enqueueStream(e, d, sel) {
    const btn = e.currentTarget;
    const original = btn.innerHTML;
    btn.disabled = true; btn.innerHTML = `<span class="spinner"></span>`;
    try {
      await window.API.startDownload({ url: d.url, title: d.title || "",
        meta: { fmt: sel.fmt, ...(d.referer || d.found_on_page ? { referer: d.referer || d.found_on_page } : {}) } });
      toast("Transfer started — watch it in the Download Manager.", "ok", { icon: "dl", actionLabel: "View", onAction: () => S.emit("nav", "view-downloads") });
    } catch (err) { toast(err.message, "err"); }
    finally { btn.disabled = false; btn.innerHTML = original; }
  }

  function renderDirect(d) {
    const card = cardShell(`FILE · ${d.file_type || "binary"}`, "#34d399");
    const body = el("div", {});
    body.append(el("h2", { class: "ac-title", style: { marginBottom: 10 } }, d.title));
    const kv = el("div", {});
    kv.append(el("div", { class: "kv" }, el("span", { class: "k" }, "Size"), el("span", { class: "v" }, d.size ? fmtBytes(d.size) : "unknown (chunked)")));
    kv.append(el("div", { class: "kv" }, el("span", { class: "k" }, "Content-type"), el("span", { class: "v" }, d.content_type || "—")));
    kv.append(el("div", { class: "kv" }, el("span", { class: "k" }, "Resume"), el("span", { class: "v" }, d.accepts_ranges ? "supported (HTTP 206)" : "unknown")));
    body.append(kv);
    body.append(el("div", { class: "ac-actions" },
      el("button", { class: "btn btn-primary", onclick: (e) => enqueue(e, d, { label: "file" }) },
        el("span", { class: "btn-ico", html: ICONS.svg("download") }), el("span", {}, "Download")),
      el("button", { class: "btn btn-ghost", onclick: async () => { window.UI.copyText(d.url); toast("Direct link copied — paste into IDM, aria2 or your browser.", "ok", { icon: "copy" }); } },
        el("span", { class: "btn-ico", html: ICONS.svg("copy") }), el("span", {}, "Copy download link")),
      el("a", { class: "btn btn-ghost", href: d.url, target: "_blank", rel: "noopener" },
        el("span", { class: "btn-ico", html: ICONS.svg("external") }), el("span", {}, "Open"))));
    const moreD = moreBlock(d);
    if (moreD) body.append(moreD);
    card.append(body);
  }

  function renderMediaError(d) {
    const card = cardShell(`MEDIA · ${d.extractor} — BLOCKED`, "#fb7185");
    card.append(el("h2", { class: "ac-title", style: { marginBottom: 6 } }, "The site is recognized, but extraction was refused"));
    card.append(el("div", { class: "notice err", style: { margin: "10px 0" },
      html: `${ICONS.svg("warning")}<div><b>${escapeHtml(d.extractor)} extractor reported:</b><br>${escapeHtml(d.error)}</div>` }));
    for (const hint of d.hints || []) {
      card.append(el("div", { class: "notice info", style: { marginBottom: 8 }, html: `${ICONS.svg("info")}<div>${hint}</div>` }));
    }
    const candBox = el("div", { id: "block-scan-box" });
    if (d.candidates?.length) renderCands(d.candidates, d, candBox);
    card.append(el("div", { class: "ac-actions" },
      el("button", { class: "btn btn-primary", onclick: () => { S.emit("nav", "view-settings"); toast("Settings → Video & capture → Browser cookies source.", "info"); } },
        el("span", { class: "btn-ico", html: ICONS.svg("settings") }), el("span", {}, "Open Settings (cookies)")),
      el("button", { class: "btn btn-ghost", onclick: analyze },
        el("span", { class: "btn-ico", html: ICONS.svg("refresh") }), el("span", {}, "Retry")),
      el("button", { class: "btn btn-ghost", onclick: async (e) => {
        const b = e.currentTarget; b.disabled = true; b.innerHTML = `<span class="spinner"></span> Deep-scan…`;
        try {
          const { candidates } = await window.API.scan(d.url);
          if (!candidates.length) toast("Deep scan found no plain media links either — the player is fully scripted.");
          renderCands(candidates, d, candBox);
        } catch (err) { toast(err.message, "err"); }
        finally { b.disabled = false; b.innerHTML = `${ICONS.svg("scan")} Deep-scan anyway`; }
      }, html: `${ICONS.svg("scan")} Deep-scan anyway` }),
      el("button", { class: "btn btn-ghost", onclick: () => { window.UI.copyText(d.url); toast("URL copied — it works in yt-dlp CLI / IDM too.", "ok", { icon: "copy" }); } },
        el("span", { class: "btn-ico", html: ICONS.svg("copy") }), el("span", {}, "Copy URL"))));
    card.append(candBox);
  }

  function renderCands(cands, d, box) {
    box.innerHTML = "";
    if (!cands?.length) return;
    const list = el("div", { class: "rc-candidates", style: { borderStyle: "solid", marginTop: 12 } });
    for (const c of cands.slice(0, 16)) {
      list.append(el("div", { class: "cand" },
        el("span", { class: "cand-name", title: c.url }, c.filename
          + (c.size_bytes ? ` · ${fmtBytes(c.size_bytes)}` : "")),
        el("button", { class: "icon-btn", title: "Analyze this link", html: ICONS.svg("scan"),
          onclick: () => { $("#direct-url").value = c.url; analyze(); } }),
        el("button", { class: "icon-btn", title: "Download directly", html: ICONS.svg("download"),
          onclick: async (e) => {
            const b = e.currentTarget; b.disabled = true;
            try { await window.API.startDownload({ url: c.url, filename: c.filename, meta: { referer: d.url } });
              toast("Added to downloads.", "ok", { icon: "dl" }); }
            catch (err) { toast(err.message, "err"); }
            finally { b.disabled = false; }
          } }),
        el("button", { class: "icon-btn", title: "Copy link", html: ICONS.svg("copy"),
          onclick: () => { window.UI.copyText(c.url); toast("Link copied.", "ok", { icon: "copy" }); } })));
    }
    box.append(el("div", { class: "ac-block-label", style: { marginTop: 14 } },
      `Media found inside the page · ${Math.min(cands.length, 16)}`), list);
  }

  function renderMagnet(d) {
    const card = cardShell("MAGNET", "#f97316");
    card.append(el("h2", { class: "ac-title", style: { marginBottom: 8 } }, d.title));
    card.append(el("p", { style: { fontSize: 13, color: "var(--text-2)", lineHeight: 1.6 } },
      "Magnet links resolve through your torrent client — WebFinder can’t stream them directly, but you can copy the link or hand it off."));
    card.append(el("div", { class: "ac-actions" },
      el("button", { class: "btn btn-primary", onclick: () => { window.UI.copyText(d.url); toast("Magnet copied.", "ok", { icon: "copy" }); } },
        el("span", { class: "btn-ico", html: ICONS.svg("copy") }), el("span", {}, "Copy magnet")),
      el("button", { class: "btn btn-ghost", onclick: () => openMagnet(d.url) },
        el("span", { class: "btn-ico", html: ICONS.svg("external") }), el("span", {}, "Open in torrent client"))));
  }

  function renderPage(d) {
    const card = cardShell("PAGE · no direct file", "#8b8b96");
    card.append(el("h2", { class: "ac-title", style: { marginBottom: 8 } }, d.title || d.url));
    if (d.site) card.append(el("div", { class: "ac-meta", style: { marginBottom: 6 } },
      el("span", { html: `${ICONS.svg("globe")} ${escapeHtml(d.site)}` })));
    card.append(el("p", { style: { fontSize: 13, color: "var(--text-2)", lineHeight: 1.7 } },
      "That URL plays its video through a script — no plain file link surfaced as a single answer."));
    const box = el("div");
    if (d.candidates?.length) renderCands(d.candidates, d, box);
    else box.append(el("div", { class: "ac-actions", style: { marginTop: 12 } },
      el("button", { class: "btn btn-primary", onclick: async (e) => {
        const b = e.currentTarget; b.disabled = true; b.innerHTML = `<span class="spinner"></span> Scanning…`;
        try {
          const { candidates } = await window.API.scan(d.url);
          if (!candidates.length) toast("Nothing plain in there — grab the real URL from DevTools → Network.");
          renderCands(candidates, d, box);
        } catch (err) { toast(err.message, "err"); }
        finally { b.disabled = false; b.innerHTML = "Deep-scan this page"; }
      }, html: `${ICONS.svg("scan")} Deep-scan this page` })));
    card.append(box);
  }

  function renderError(msg) {
    const card = cardShell("UNREACHABLE", "#fb7185");
    card.append(el("div", { class: "notice err", style: { marginBottom: 12 }, html: `${ICONS.svg("warning")}<div>${escapeHtml(msg)}</div>` }));
    card.append(el("p", { style: { fontSize: 12.5, color: "var(--text-3)", lineHeight: 1.6 } },
      "Tips: some hosts block foreign/datacenter IPs (a proxy in Settings → Downloads routes everything), TLS handshakes die behind corporate gateways, and a link that redirects to a JS-only player may expose no file at all — copy the real URL from the browser\u2019s DevTools Network tab and paste it here."));
  }

  /* ── actions ─────────────────────────────────────────────────────────────── */
  async function enqueue(btnEvent, d, fmt) {
    const btn = btnEvent.currentTarget;
    const original = btn.innerHTML;
    btn.disabled = true;
    btn.innerHTML = `<span class="spinner"></span>`;
    try {
      const body = { url: d.url, title: d.title || "" };
      if (d.kind === "media") body.kind = "ytdlp";
      if (d.kind === "hls") body.kind = "hls";
      if (d.kind === "media" && fmt) {
        body.meta = { format: fmt.format, quality: fmt.label };
        if (d.extractor_args) body.meta.extractor_args = d.extractor_args;
        if (d.referer || d.found_on_page) body.meta.referer = d.referer || d.found_on_page;
      }
      await window.API.startDownload(body);
      toast("Transfer started — watch it in the Download Manager.", "ok", { icon: "dl", actionLabel: "View", onAction: () => S.emit("nav", "view-downloads") });
    } catch (err) {
      toast(err.message, "err");
    } finally {
      btn.disabled = false; btn.innerHTML = original;
    }
  }

  function openMagnet(url) {
    try { window.location = url; toast("Handed the magnet to your OS — open your torrent client if nothing happened.", "info"); }
    catch { /* noop */ }
  }

  function escapeHtml(s) { const d = document.createElement("div"); d.textContent = s ?? ""; return d.innerHTML; }

  window.DirectView = { init, analyze };
})();
