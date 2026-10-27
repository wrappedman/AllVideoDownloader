/* All Video Downloader · views/downloads.js — Download Manager: animated rows, stats, sparkline */
(function () {
  const { $, el, fmtBytes, fmtSpeed, fmtEta, animateNumber } = window.UI;
  const S = window.Store;

  const rows = new Map();          // job id → DOM row
  let chart = { canvas: null, ctx: null, hist: [], raf: 0 };

  let syncing = false;

  function init() {
    S.on("job.update", upsert);
    S.on("jobs.hydrated", () => { rows.clear(); $("#dl-list").innerHTML = ""; S.jobs.forEach((j) => upsert(j, true)); paint(); });
    $("#dl-clear").addEventListener("click", async () => { await window.API.clearFinished(); });
    $("#dl-pause-all").addEventListener("click", () => bulk("pause"));
    $("#dl-resume-all").addEventListener("click", () => bulk("resume"));
    chart.canvas = $("#speed-chart");
    try { chart.ctx = chart.canvas.getContext("2d"); } catch { chart.ctx = null; }
    resizeChart(); addEventListener("resize", resizeChart);
    tickChart();
    paint();
  }

  async function bulk(action) {
    for (const j of S.jobs.values()) {
      if (action === "pause" && ["downloading", "queued"].includes(j.status)) await window.API.jobAction(j.id, "pause");
      if (action === "resume" && j.status === "paused") await window.API.jobAction(j.id, "resume");
    }
  }

  /* ── row lifecycle ──────────────────────────────────────────────────────── */
  function upsert(job, replace = false) {
    if (!job) return;
    let row = rows.get(job.id);
    if (!row) {
      const tpl = $("#tpl-dl-row");
      row = tpl.content.firstElementChild.cloneNode(true);
      row.dataset.id = job.id;
      rows.set(job.id, row);
      if (replace) $("#dl-list").append(row);
      else $("#dl-list").prepend(row);
      wireRow(row);
    }
    paintRow(row, job);
    paint();
  }

  function paintRow(row, j) {
    const ftColor = (S.meta?.file_types?.[j.file_type] || {}).color || "#a78bfa";
    row.style.setProperty("--ft-c", ftColor);
    const typeIcon = row.querySelector(".dl-type");
    const icon = { video: "video", image: "image", torrent: "torrent", magnet: "magnet", software: "app", archive: "archive", audio: "audio", ebook: "book" }[j.file_type] || "download";
    if (typeIcon.dataset.icon !== icon) { typeIcon.dataset.icon = icon; typeIcon.innerHTML = ICONS.svg(icon); }

    row.querySelector(".dl-name").textContent = j.title || j.filename;
    const st = row.querySelector(".dl-status");
    st.className = `dl-status st-${j.status}`;
    st.textContent = { downloading: j.kind === "hls" ? "capturing" : j.kind === "ytdlp" ? "merging" : j.kind === "sim" ? "transferring" : "downloading",
                       queued: "queued", paused: "paused", done: "done", error: "error", cancelled: "cancelled" }[j.status] || j.status;

    row.classList.toggle("is-done", j.status === "done");
    row.classList.toggle("is-paused", j.status === "paused");
    row.classList.toggle("is-error", j.status === "error" || j.status === "cancelled");

    const wasPct = parseFloat(row.dataset.pct || "0");
    row.querySelector(".dl-progress").style.setProperty("--p", `${j.progress_pct || 0}%`);
    row.dataset.pct = j.progress_pct || 0;
    if (wasPct < 100 && (j.status === "done")) flash(row);

    const timeUnit = j.unit === "time";
    const done = timeUnit ? `${fmtEta(j.done)} / ${fmtEta(j.total)}` : `${fmtBytes(j.done)}${j.total ? ` / ${fmtBytes(j.total)}` : ""}`;
    row.querySelector(".dl-done").innerHTML = `<b>${done}</b> · ${Math.round(j.progress_pct || 0)}%${j.meta?.quality ? ` · ${j.meta.quality}` : ""}${j.meta?.simulated ? ' · <span title="The real transfer was simulated (network unavailable)">simulated</span>' : ""}`;
    row.querySelector(".dl-speed").innerHTML = j.status === "downloading" ? `${ICONS.svg("zap")} ${fmtSpeed(j.speed, j.unit)}` : "";
    row.querySelector(".dl-eta").innerHTML = j.status === "downloading" && j.eta ? `${ICONS.svg("clock")} ${fmtEta(j.eta)}` : (j.status === "error" ? `<span style="color:var(--danger)">${escapeHtml(j.error || "failed")}</span>` : "");

    const toggle = row.querySelector(".dl-toggle");
    const wantPause = ["downloading", "queued"].includes(j.status);
    const key = wantPause ? "pause" : j.status === "paused" ? "play" : j.status === "done" ? "check" : "play";
    if (toggle.dataset.icon !== key) { toggle.dataset.icon = key; toggle.innerHTML = ICONS.svg(key); }
    toggle.classList.toggle("danger", j.status === "paused");
    row.querySelector(".dl-reveal").innerHTML = ICONS.svg("copy");
    row.querySelector(".dl-open").innerHTML = ICONS.svg("folder");
    row.querySelector(".dl-remove").innerHTML = ICONS.svg(j.status === "done" ? "trash" : "x");
    row.querySelector(".dl-remove").classList.add("danger");
    if (["error", "cancelled"].includes(j.status)) {
      if (!row.querySelector(".dl-retry")) {
        const retry = el("button", { class: "icon-btn dl-retry", title: "Retry", html: ICONS.svg("refresh") });
        retry.addEventListener("click", () => window.API.jobAction(j.id, "retry"));
        row.querySelector(".dl-actions").prepend(retry);
      }
    } else row.querySelector(".dl-retry")?.remove();
  }

  function wireRow(row) {
    const id = () => row.dataset.id;
    row.querySelector(".dl-toggle").addEventListener("click", () => {
      const j = S.jobs.get(id()); if (!j) return;
      if (["downloading", "queued"].includes(j.status)) window.API.jobAction(j.id, "pause");
      else if (j.status === "paused") window.API.jobAction(j.id, "resume");
      else if (j.status === "error" || j.status === "cancelled") window.API.jobAction(j.id, "retry");
    });
    row.querySelector(".dl-open").addEventListener("click", () => {
      const j = S.jobs.get(id());
      // the backend runs on this same machine, so folder reveal works in BOTH modes;
      // if the tracked path went stale (post-merge rename), open the downloads dir
      window.API.openPath(j?.filepath || "").catch(() =>
        window.API.openPath("").catch((e2) => window.UI.toast(String(e2?.message || "Could not open the folder."), "warn")));
    });
    row.querySelector(".dl-reveal").addEventListener("click", async () => {
      const j = S.jobs.get(id()); if (j) { await window.UI.copyText(j.url); window.UI.toast("Source URL copied.", "ok", { icon: "copy" }); }
    });
    row.querySelector(".dl-remove").addEventListener("click", () => {
      row.classList.add("removing");
      setTimeout(() => window.API.removeJob(id()), 240);
      rows.delete(id()); row.remove(); paint();
    });
  }

  function flash(row) { row.classList.add("finished-flash"); setTimeout(() => row.classList.remove("finished-flash"), 950); }

  /* ── header stats + empty state ─────────────────────────────────────────── */
  function paint() {
    let active = 0, done = 0;
    S.jobs.forEach((j) => { if (["downloading", "queued", "paused"].includes(j.status)) active++; if (j.status === "done") done++; });
    animateNumber($("#st-active"), active);
    animateNumber($("#st-done"), done);
    const speed = S.totalSpeed();
    $("#st-speed").innerHTML = `${fmtBytes(speed, 1)}<i>/s</i>`;
    animateNumber($("#st-saved"), S.totalSaved(), (v) => fmtBytes(v));  // textContent sink — no HTML here (that showed a literal <i>)
    $("#dl-subtitle").textContent = active
      ? `${active} in the pipe — controls live on each row; every transfer also keeps its source link one click away.`
      : (S.jobs.size ? "Idle. Retry or clear finished transfers to tidy the list." : "Nothing in flight — paste a link and hit Grab it.");
    const empty = $("#dl-empty");
    if (!S.jobs.size) {
      empty.classList.remove("hidden");
      empty.innerHTML = "";
      empty.append(el("div", { class: "re-ico", html: ICONS.svg("download") }),
        el("h3", {}, "The queue is empty"),
        el("p", {}, "Downloads you start from Search or the Smart Direct link land here — with pause/resume, live speed metrics and a one-click copy of the direct URL for IDM."));
    } else {
      empty.classList.add("hidden");
      const list = document.getElementById("dl-list");
      if (!syncing && list.childElementCount !== S.jobs.size) {    // reorder only on structural change
        syncing = true;
        list.innerHTML = ""; rows.clear();
        [...S.jobs.values()].reverse().forEach((j) => upsert(j, true));
        syncing = false;
      }
    }
    window.App?.syncRail?.();
  }

  /* ── sparkline ───────────────────────────────────────────────────────────── */
  function resizeChart() {
    const c = chart.canvas; if (!c) return;
    const dpr = devicePixelRatio || 1;
    c.width = 180 * dpr; c.height = 34 * dpr;
    chart.ctx?.setTransform(dpr, 0, 0, dpr, 0, 0);
  }
  let lastSample = 0;
  function tickChart() {
    if (!chart.ctx) return;
    const now = performance.now();
    if (now - lastSample > 120) {
      lastSample = now;
      chart.hist.push(S.totalSpeed());
      if (chart.hist.length > 60) chart.hist.shift();
      drawChart();
      const gw = $("#global-speed");
      if (gw) {
        const speed = S.totalSpeed();
        gw.classList.toggle("hidden", !speed);
        $("#sw-value").textContent = fmtBytes(speed, 1).split(" ")[0];
        $("#sw-unit").textContent = fmtBytes(speed, 1).split(" ").slice(1).join(" ") + "/s";
      }
    }
    requestAnimationFrame(tickChart);
  }
  function drawChart() {
    const { ctx, hist } = chart;
    if (!ctx) return;
    const W = 180, H = 34;
    ctx.clearRect(0, 0, W, H);
    const max = Math.max(1, ...hist) * 1.15;
    const grad = ctx.createLinearGradient(0, 0, W, 0);
    const accent = getComputedStyle(document.documentElement).getPropertyValue("--accent").trim() || "#a855f7";
    grad.addColorStop(0, accent + "22"); grad.addColorStop(1, accent);
    ctx.beginPath();
    hist.forEach((v, i) => {
      const x = (i / Math.max(1, hist.length - 1)) * W;
      const y = H - (v / max) * (H - 4) - 2;
      i ? ctx.lineTo(x, y) : ctx.moveTo(x, y);
    });
    ctx.strokeStyle = grad; ctx.lineWidth = 1.8; ctx.lineJoin = "round"; ctx.stroke();
    if (hist.length > 1) {
      ctx.lineTo(W, H); ctx.lineTo(0, H); ctx.closePath();
      const fill = ctx.createLinearGradient(0, 0, 0, H);
      fill.addColorStop(0, accent + "33"); fill.addColorStop(1, accent + "00");
      ctx.fillStyle = fill; ctx.fill();
    }
  }
  function escapeHtml(s) { const d = document.createElement("div"); d.textContent = s ?? ""; return d.innerHTML; }

  window.DownloadsView = { init, paint };
})();
