/* All Video Downloader · state.js — tiny observable store + cross-view events */
(function () {
  const listeners = new Map();
  const Store = {
    settings: {},
    meta: null,
    jobs: new Map(),          // id → job dict

    on(evt, fn) {
      if (!listeners.has(evt)) listeners.set(evt, new Set());
      listeners.get(evt).add(fn);
      return () => listeners.get(evt)?.delete(fn);
    },
    emit(evt, data) {
      listeners.get(evt)?.forEach((fn) => { try { fn(data); } catch (e) { console.error(e); } });
    },

    /* aggregate helpers ------------------------------------------------ */
    get activeCount() {
      let n = 0;
      this.jobs.forEach((j) => { if (["downloading", "queued", "paused"].includes(j.status)) n++; });
      return n;
    },
    totalSpeed() {
      let s = 0;
      this.jobs.forEach((j) => { if (j.status === "downloading" && j.unit === "bytes") s += j.speed || 0; });
      return s;
    },
    totalSaved() {
      let b = 0;
      // byte-unit finished jobs only — an HLS job's `done` is SECONDS and used
      // to leak into this sum as "bytes", scrambling the saved-size stat.
      this.jobs.forEach((j) => { if (j.status === "done" && j.unit !== "time") b += (j.done || j.total || 0); });
      return b;
    },
    upsertJob(j) {
      const prev = this.jobs.get(j.id);
      this.jobs.set(j.id, j);
      if (!prev || prev.status !== j.status) this.emit("job.status", { job: j, prev });
      this.emit("job.update", j);
    },
    hydrateJobs(list) {
      this.jobs.clear();
      (list || []).forEach((j) => this.jobs.set(j.id, j));
      this.emit("jobs.hydrated");
    },
  };

  /* bridge every manager event coming off the WebSocket -------------------- */
  window.API.ws.on((evt) => {
    switch (evt.type) {
      case "bootstrap":
      case "jobs.list":
        Store.hydrateJobs(evt.payload.jobs); break;
      case "job.update":
        Store.upsertJob(evt.payload); break;
      case "ws.connected":
        document.getElementById("net-status")?.classList.remove("offline"); break;
      case "ws.disconnected":
        document.getElementById("net-status")?.classList.add("offline"); break;
    }
  });

  window.Store = Store;
})();
