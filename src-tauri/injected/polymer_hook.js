// Capture YouTube Music's Polymer store onto window.__YTMD_HOOK__
(function () {
  try {
    var fakeBaseClass = function () {
      try {
        if (!window.__YTMD_HOOK__) {
          if (
            this.store &&
            !!this.store.getState &&
            !!this.store.dispatch &&
            !!this.store.subscribe
          ) {
            var ytmdHook = { ytmStore: this.store };
            Object.freeze(ytmdHook);
            window.__YTMD_HOOK__ = ytmdHook;
          }
        }
      } catch (e) {}
    };
    Object.defineProperty(window, "PolymerFakeBaseClassWithoutHtml", {
      set: function () {},
      get: function () {
        return fakeBaseClass;
      },
    });
  } catch (e) {}
})();
