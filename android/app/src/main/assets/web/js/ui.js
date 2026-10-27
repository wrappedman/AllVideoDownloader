/* WebFinder · ui.js — DOM helpers, formatting, toasts, modals */
(function () {
  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
  function el(tag, attrs = {}, ...kids) {
    const n = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs || {})) {
      if (v === false || v == null) continue;
      if (k === "class") n.className = v;
      else if (k === "html") n.innerHTML = v;
      else if (k.startsWith("on")) n.addEventListener(k.slice(2).toLowerCase(), v);
      else if (k === "style" && typeof v === "object") {
        for (const [prop, val] of Object.entries(v)) {
          if (prop.startsWith("--")) n.style.setProperty(prop, String(val));
          else n.style[prop] = val;
        }
      }
      else n.setAttribute(k, v);
    }
    for (const kid of kids.flat(2)) {
      if (kid == null || kid === false) continue;
      n.append(kid.nodeType ? kid : document.createTextNode(kid));
    }
    return n;
  }

  /* ── formatting ─────────────────────────────────────────────────────────── */
  function fmtBytes(n, digits = 1) {
    if (n == null || isNaN(n)) return "—";
    if (n < 1024) return `${n} B`;
    const units = ["KB", "MB", "GB", "TB"];
    let u = -1;
    do { n /= 1024; u++; } while (n >= 1024 && u < units.length - 1);
    return `${n.toFixed(n >= 100 || u === 0 ? 0 : digits)} ${units[u]}`;
  }
  function fmtSpeed(bytesPerSec, unit = "bytes") {
    if (unit === "time") return `${(bytesPerSec || 0).toFixed ? bytesPerSec.toFixed(1) : bytesPerSec}× realtime`;
    if (!bytesPerSec) return "0 B/s";
    return `${fmtBytes(bytesPerSec, 0)}/s`;
  }
  function fmtEta(sec) {
    if (!sec && sec !== 0) return "";
    sec = Math.round(sec);
    if (sec > 86400 * 30) return "estimating…";
    const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
    if (h) return `${h}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
    return m ? `${m}m ${String(s).padStart(2, "0")}s` : `${s}s`;
  }
  function fmtDuration(sec) {
    if (!sec) return "—";
    sec = Math.round(sec);
    const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
    return (h ? `${h}:` : "") + String(m).padStart(h ? 2 : 1, "0") + ":" + String(s).padStart(2, "0");
  }
  const fmtTime = (ts) => new Date(ts * 1000).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });

  /* ── clipboard (secure-context agnostic) ───────────────────────────────── */
  async function copyText(text) {
    try {
      if (navigator.clipboard && window.isSecureContext) { await navigator.clipboard.writeText(text); return true; }
      const ta = el("textarea", { style: { position: "fixed", opacity: 0, pointerEvents: "none" } });
      ta.value = text; document.body.append(ta); ta.select();
      const ok = document.execCommand("copy"); ta.remove();
      return ok;
    } catch { return false; }
  }

  /* ── toasts ─────────────────────────────────────────────────────────────── */
  const TOAST_ICONS = { ok: "check", err: "warning", info: "info", warn: "warning", dl: "download", copy: "copy" };
  function toast(msg, kind = "info", opts = {}) {
    const stack = $("#toast-stack");
    const t = el("div", { class: `toast ${kind}` },
      el("span", { class: "t-ico", html: ICONS.svg(TOAST_ICONS[opts.icon] || TOAST_ICONS[kind] || "info") }),
      el("span", { class: "t-msg" }, msg),
      opts.actionLabel ? el("button", { class: "t-action", onclick: () => { opts.onAction?.(); dismiss(); } }, opts.actionLabel) : null,
    );
    let dismissed = false;
    const dismiss = () => { if (dismissed) return; dismissed = true; t.classList.add("out"); setTimeout(() => t.remove(), 300); };
    stack.append(t);
    const ttl = opts.ttl ?? (kind === "err" ? 6500 : 3600);
    setTimeout(dismiss, ttl);
    t.addEventListener("mouseenter", () => clearTimeout(t._timer));
    return dismiss;
  }

  /* ── modal ──────────────────────────────────────────────────────────────── */
  function modal({ title, body, actions = [] }) {
    const root = $("#modal-root");
    root.classList.remove("hidden");
    const close = () => { root.classList.add("hidden"); root.innerHTML = ""; };
    const m = el("div", { class: "modal", role: "dialog", "aria-modal": "true" },
      el("h3", {}, title),
      body,
      el("div", { class: "modal-actions" },
        ...actions.map((a) => el("button", {
          class: `btn ${a.primary ? "btn-primary" : "btn-ghost"}`,
          onclick: () => { a.onClick?.(); if (a.close !== false) close(); },
        }, a.label))),
    );
    root.innerHTML = "";
    root.append(m);
    root.onclick = (e) => { if (e.target === root) close(); };
    const esc = (e) => { if (e.key === "Escape") { close(); removeEventListener("keydown", esc); } };
    addEventListener("keydown", esc);
    return close;
  }

  /* ── misc ───────────────────────────────────────────────────────────────── */
  const debounce = (fn, ms = 200) => { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; };
  function animateNumber(node, to, fmt = (v) => v) {
    const from = parseFloat(node.dataset.v || "0") || 0;
    if (from === to) { node.textContent = fmt(to); return; }
    node.dataset.v = to;
    const t0 = performance.now(), dur = 420;
    const step = (now) => {
      const k = Math.min(1, (now - t0) / dur), e = 1 - Math.pow(1 - k, 3);
      node.textContent = fmt(from + (to - from) * e);
      if (k < 1) requestAnimationFrame(step);
    };
    requestAnimationFrame(step);
  }
  /** attach a spotlight-follow gradient to .card elements */
  function spotlightify(root = document) {
    root.addEventListener("pointermove", (e) => {
      const card = e.target.closest?.(".result-card, .dl-row, .stat-card");
      if (!card) return;
      const r = card.getBoundingClientRect();
      card.style.setProperty("--mx", `${((e.clientX - r.left) / r.width) * 100}%`);
    });
  }
  const faviconUrl = (domain) => `https://www.google.com/s2/favicons?domain=${domain}&sz=64`;

  window.UI = { $, $$, el, fmtBytes, fmtSpeed, fmtEta, fmtDuration, fmtTime, copyText, toast, modal, debounce, animateNumber, spotlightify, faviconUrl };
})();
