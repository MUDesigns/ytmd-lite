// Bridge: player state + RPC replies to Rust via title channel / IPC.
(function () {
  var STATE_PREFIX = "YTMD_STATE:";
  var RPC_PREFIX = "YTMD_RPC:";
  var DATA_PREFIX = "YTMD_DATA:";

  function postTitle(prefix, payload) {
    try {
      document.title = prefix + JSON.stringify(payload);
    } catch (e) {}
  }

  function emitPlayer(payload) {
    try {
      if (window.chrome && window.chrome.webview && window.chrome.webview.postMessage) {
        window.chrome.webview.postMessage(
          JSON.stringify({ event: "ytm-player-state", payload: payload }),
        );
      }
    } catch (e) {}
    postTitle(STATE_PREFIX, payload);
    try {
      if (window.__TAURI__ && window.__TAURI__.event && window.__TAURI__.event.emit) {
        window.__TAURI__.event.emit("ytm-player-state", payload);
      }
    } catch (e) {}
    try {
      if (window.__TAURI_INTERNALS__ && typeof window.__TAURI_INTERNALS__.invoke === "function") {
        window.__TAURI_INTERNALS__.invoke("ingest_player_state", { payload: payload });
      }
    } catch (e) {}
    window.__YTMD_LITE_STATE__ = payload;
  }

  function replyRpc(id, ok, payload, error) {
    var body = { id: id, ok: !!ok };
    if (ok) body.payload = payload;
    else body.error = String(error || "error");
    postTitle(RPC_PREFIX, body);
    try {
      if (window.__TAURI_INTERNALS__ && typeof window.__TAURI_INTERNALS__.invoke === "function") {
        window.__TAURI_INTERNALS__.invoke("ingest_rpc_reply", { reply: body });
      }
    } catch (e) {}
  }

  function emitData(kind, payload) {
    postTitle(DATA_PREFIX, { kind: kind, payload: payload });
  }

  window.ytmd = window.ytmd || {};
  window.ytmd.emitPlayerState = emitPlayer;
  window.ytmd.replyRpc = replyRpc;
  window.ytmd.emitData = emitData;

  window.__YTMD_RPC__ = {
    call: async function (id, method, args) {
      try {
        if (!window.__YTMD_API__ || typeof window.__YTMD_API__[method] !== "function") {
          throw new Error("Unknown method: " + method);
        }
        var result = await window.__YTMD_API__[method](args || {});
        replyRpc(id, true, result, null);
      } catch (e) {
        replyRpc(id, false, null, (e && e.message) || String(e));
      }
    },
  };
})();
