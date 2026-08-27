// Hide YouTube Music's stock player chrome — we use the Material IDE dock instead.
(function () {
  var STYLE_ID = "ytmd-lite-hide-native-player";

  function apply() {
    var el = document.getElementById(STYLE_ID);
    if (!el) {
      el = document.createElement("style");
      el.id = STYLE_ID;
      (document.head || document.documentElement).appendChild(el);
    }
    el.textContent = [
      "ytmusic-player-bar,",
      "#player-bar-background {",
      "  display: none !important;",
      "  visibility: hidden !important;",
      "  height: 0 !important;",
      "  min-height: 0 !important;",
      "  max-height: 0 !important;",
      "  opacity: 0 !important;",
      "  pointer-events: none !important;",
      "}",
      "ytmusic-app-layout {",
      "  --ytmusic-player-bar-height: 0px !important;",
      "}",
      "ytmusic-app {",
      "  --ytmusic-player-bar-height: 0px !important;",
      "  --ytmusic-base-page-padding-bottom: 0px !important;",
      "}",
    ].join("\n");
  }

  apply();
  document.addEventListener("DOMContentLoaded", apply);
  var obs = new MutationObserver(function () {
    if (!document.getElementById(STYLE_ID)) apply();
  });
  try {
    obs.observe(document.documentElement, { childList: true, subtree: true });
  } catch (e) {}
})();
