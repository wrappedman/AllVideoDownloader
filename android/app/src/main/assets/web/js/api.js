/* All Video Downloader · api.js — REST + WebSocket bridge with graceful desktop/browser handling */
(function () {
  const BASE = "";

  async function req(method, path, body) {
    const opts = { method, headers: {} };
    if (body !== undefined) {
      opts.headers["Content-Type"] = "application/json";
      opts.body = JSON.stringify(body);
    }
    const res = await fetch(BASE + path, opts);
    if (!res.ok) {
      let detail = `${res.status}`;
      try { detail = (await res.json()).detail || detail; } catch { /* noop */ }
      throw new Error(typeof detail === "string" ? detail : JSON.stringify(detail));
    }
    return res.status === 204 ? null : res.json();
  }

  const API = {
    meta: () => req("GET", "/api/meta"),
    getSettings: () => req("GET", "/api/settings"),
    saveSettings: (patch) => req("PUT", "/api/settings", { patch }),
    analyze: (url) => req("POST", "/api/analyze", { url }),
    analyzeDownload: (url, title, format) => req("POST", "/api/analyze/download", { url, title, format }),
    startDownload: (payload) => req("POST", "/api/downloads", payload),
    listDownloads: () => req("GET", "/api/downloads"),
    jobAction: (id, action) => req("POST", `/api/downloads/${id}/${action}`),
    removeJob: (id) => req("DELETE", `/api/downloads/${id}`),
    clearFinished: () => req("POST", "/api/downloads/clear"),
    openPath: (p) => req("POST", `/api/system/open-path${p ? `?path=${encodeURIComponent(p)}` : ""}`),
    exit: () => req("POST", "/api/system/exit"),
    scan: (url) => req("POST", "/api/scan", { url }),
    selfupdate: () => req("POST", "/api/system/selfupdate"),

    /* ── cookies (used by the download engine) ──────────────────────────── */
    cookiesStatus: () => req("GET", "/api/cookies/status"),
    cookiesProbe: () => req("GET", "/api/cookies/probe"),
    cookiesUseBest: () => req("POST", "/api/cookies/use-best"),
  };

  /* ── desktop bridge: window controls & native dialogs via pywebview ──── */
  function bridge() {
    const py = () => (window.pywebview && window.pywebview.api) || null;
    return {
      get isDesktop() { return !!window.__WEBFINDER_DESKTOP__; },
      minimize: async () => { const a = py(); if (a) return a.minimize(); },
      toggleMaximize: async () => { const a = py(); if (a) return a.toggle_maximize(); },
      close: async () => { const a = py(); if (a) return a.close(); },
      pickFolder: async () => { const a = py(); if (a) return a.pick_folder(); },
    };
  }
  API.bridge = bridge();

  /* ── realtime: WebSocket with automatic polling fallback ──────────────── */
  const WS = {
    handlers: new Set(),
    connected: false,
    _pollTimer: null,
    _retry: 0,
    on(fn) { this.handlers.add(fn); return () => this.handlers.delete(fn); },
    _dispatch(evt) { this.handlers.forEach((h) => { try { h(evt); } catch (e) { console.error(e); } }); },
    connect() {
      const proto = location.protocol === "https:" ? "wss:" : "ws:";
      const ws = new WebSocket(`${proto}//${location.host}/ws`);
      ws.onopen = () => {
        this.connected = true; this._retry = 0;
        this._dispatch({ type: "ws.connected" });
        this._stopPolling();
        setInterval(() => { if (ws.readyState === 1) ws.send("ping"); }, 25000);
      };
      ws.onmessage = (m) => { try { this._dispatch(JSON.parse(m.data)); } catch { /* noop */ } };
      ws.onclose = () => {
        this.connected = false;
        this._dispatch({ type: "ws.disconnected" });
        this._startPolling();                       // keep progress flowing anyway
        setTimeout(() => this.connect(), Math.min(10000, 600 * 2 ** this._retry++));
      };
      ws.onerror = () => { try { ws.close(); } catch { /* noop */ } };
    },
    _startPolling() {
      if (this._pollTimer) return;
      this._pollTimer = setInterval(async () => {
        try {
          const { jobs } = await API.listDownloads();
          this._dispatch({ type: "jobs.list", payload: { jobs } });
        } catch { /* offline */ }
      }, 1400);
    },
    _stopPolling() { if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; } },
  };
  API.ws = WS;
  window.API = API;
})();
