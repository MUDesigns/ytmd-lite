// Apply chosen audio output (sink) to YTM media elements / AudioContexts.
(function () {
  function applySink(sinkId) {
    if (typeof sinkId !== "string") return;
    window.__YTMD_AUDIO_SINK__ = sinkId;

    function setEl(el) {
      try {
        if (el && typeof el.setSinkId === "function") {
          el.setSinkId(sinkId).catch(function () {});
        }
      } catch (e) {}
    }

    document.querySelectorAll("video, audio").forEach(setEl);

    // Hook future elements
    if (!window.__YTMD_SINK_OBS__) {
      window.__YTMD_SINK_OBS__ = new MutationObserver(function (mutations) {
        mutations.forEach(function (m) {
          m.addedNodes.forEach(function (n) {
            if (n.nodeType !== 1) return;
            if (n.matches && (n.matches("video") || n.matches("audio"))) setEl(n);
            if (n.querySelectorAll) n.querySelectorAll("video, audio").forEach(setEl);
          });
        });
      });
      try {
        window.__YTMD_SINK_OBS__.observe(document.documentElement, {
          childList: true,
          subtree: true,
        });
      } catch (e) {}
    }

    // Patch AudioContext constructor to prefer this sink when supported
    try {
      if (window.AudioContext && !window.__YTMD_AC_PATCHED__) {
        window.__YTMD_AC_PATCHED__ = true;
        var Orig = window.AudioContext;
        window.AudioContext = function (opts) {
          opts = opts || {};
          if (window.__YTMD_AUDIO_SINK__ && opts.sinkId === undefined) {
            opts = Object.assign({}, opts, { sinkId: window.__YTMD_AUDIO_SINK__ });
          }
          var ctx = new Orig(opts);
          if (window.__YTMD_AUDIO_SINK__ && typeof ctx.setSinkId === "function") {
            ctx.setSinkId(window.__YTMD_AUDIO_SINK__).catch(function () {});
          }
          return ctx;
        };
        window.AudioContext.prototype = Orig.prototype;
        if (window.webkitAudioContext) {
          window.webkitAudioContext = window.AudioContext;
        }
      }
    } catch (e) {}
  }

  window.__YTMD_APPLY_AUDIO_SINK__ = applySink;

  // Re-apply periodically in case YTM recreates players
  setInterval(function () {
    if (window.__YTMD_AUDIO_SINK__) applySink(window.__YTMD_AUDIO_SINK__);
  }, 2000);
})();
