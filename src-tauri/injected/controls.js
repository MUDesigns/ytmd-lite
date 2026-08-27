// Playback helpers: seek, volume, like, shuffle, repeat, play by id.
(function () {
  function playerBar() {
    return document.querySelector("ytmusic-app-layout>ytmusic-player-bar");
  }

  function playerApi() {
    var bar = playerBar();
    return bar && bar.playerApi;
  }

  function navigateWatch(videoId, playlistId) {
    var watchEndpoint = { videoId: videoId };
    if (playlistId) watchEndpoint.playlistId = playlistId;
    document.dispatchEvent(
      new CustomEvent("yt-navigate", {
        bubbles: true,
        cancelable: true,
        composed: true,
        detail: { endpoint: { watchEndpoint: watchEndpoint } },
      }),
    );
    try {
      var app = document.querySelector("ytmusic-app");
      if (app) {
        app.dispatchEvent(
          new CustomEvent("yt-navigate", {
            bubbles: true,
            cancelable: true,
            composed: true,
            detail: { endpoint: { watchEndpoint: watchEndpoint } },
          }),
        );
      }
    } catch (e) {}
  }

  function navigateBrowse(browseId, params) {
    var browseEndpoint = { browseId: browseId };
    if (params) browseEndpoint.params = params;
    document.dispatchEvent(
      new CustomEvent("yt-navigate", {
        bubbles: true,
        cancelable: true,
        composed: true,
        detail: { endpoint: { browseEndpoint: browseEndpoint } },
      }),
    );
  }

  function tryLoadVideo(videoId, playlistId) {
    var api = playerApi();
    if (!api) return false;
    try {
      if (typeof api.loadVideoById === "function") {
        if (playlistId) {
          try {
            api.loadVideoById({ videoId: videoId, list: playlistId });
          } catch (e1) {
            api.loadVideoById(videoId);
          }
        } else {
          api.loadVideoById(videoId);
        }
        try {
          api.playVideo();
        } catch (e2) {}
        return true;
      }
    } catch (e) {}
    return false;
  }

  window.__YTMD_API__ = window.__YTMD_API__ || {};

  window.__YTMD_API__.playPause = async function () {
    var bar = playerBar();
    if (!bar || !bar.playerApi) return { ok: false };
    if (bar.playing) bar.playerApi.pauseVideo();
    else bar.playerApi.playVideo();
    return { ok: true };
  };

  window.__YTMD_API__.play = async function () {
    var api = playerApi();
    if (api) api.playVideo();
    return { ok: !!api };
  };

  window.__YTMD_API__.pause = async function () {
    var api = playerApi();
    if (api) api.pauseVideo();
    return { ok: !!api };
  };

  window.__YTMD_API__.next = async function () {
    var api = playerApi();
    if (api) api.nextVideo();
    return { ok: !!api };
  };

  window.__YTMD_API__.previous = async function () {
    var api = playerApi();
    if (api) api.previousVideo();
    return { ok: !!api };
  };

  window.__YTMD_API__.seekTo = async function (args) {
    var api = playerApi();
    var seconds = Number(args && args.seconds);
    if (api && !isNaN(seconds)) api.seekTo(seconds);
    return { ok: !!api };
  };

  window.__YTMD_API__.setVolume = async function (args) {
    var api = playerApi();
    var v = Math.max(0, Math.min(100, Number(args && args.volume)));
    if (api && !isNaN(v)) api.setVolume(v);
    return { ok: !!api, volume: v };
  };

  window.__YTMD_API__.mute = async function () {
    var api = playerApi();
    if (api) api.mute();
    return { ok: !!api };
  };

  window.__YTMD_API__.unMute = async function () {
    var api = playerApi();
    if (api) api.unMute();
    return { ok: !!api };
  };

  window.__YTMD_API__.shuffle = async function () {
    var bar = playerBar();
    if (bar && bar.queue && typeof bar.queue.shuffle === "function") {
      bar.queue.shuffle();
      return { ok: true };
    }
    return { ok: false };
  };

  window.__YTMD_API__.toggleRepeat = async function () {
    var bar = playerBar();
    try {
      var btn = bar && bar.querySelector('[aria-label*="Repeat"], .repeat');
      if (btn) {
        btn.click();
        return { ok: true };
      }
    } catch (e) {}
    return { ok: false };
  };

  window.__YTMD_API__.toggleLike = async function () {
    try {
      var ytmStore = window.__YTMD_HOOK__ && window.__YTMD_HOOK__.ytmStore;
      var bar = playerBar();
      if (!bar || !bar.playerApi) return { ok: false };
      var videoId = bar.playerApi.getPlayerResponse().videoDetails.videoId;
      var likeButtonData = bar.querySelector("ytmusic-like-button-renderer").data;
      var likeServiceEndpoint = null;
      var indifferentServiceEndpoint = null;
      for (var i = 0; i < likeButtonData.serviceEndpoints.length; i++) {
        var endpoint = likeButtonData.serviceEndpoints[i];
        if (endpoint.likeEndpoint.status === "LIKE") likeServiceEndpoint = endpoint;
        else if (endpoint.likeEndpoint.status === "INDIFFERENT")
          indifferentServiceEndpoint = endpoint;
      }
      var defaultLikeStatus = likeButtonData.likeStatus;
      var likeStatus = defaultLikeStatus;
      if (ytmStore) {
        var state = ytmStore.getState();
        if (state.likeStatus && state.likeStatus.videos && state.likeStatus.videos[videoId]) {
          likeStatus = state.likeStatus.videos[videoId];
        }
      }
      var target =
        likeStatus === "LIKE" ? indifferentServiceEndpoint : likeServiceEndpoint;
      if (!target) return { ok: false };
      document.querySelector("ytmusic-like-button-renderer").dispatchEvent(
        new CustomEvent("yt-action", {
          bubbles: true,
          cancelable: false,
          composed: true,
          detail: {
            actionName: "yt-service-request",
            args: [document.querySelector("ytmusic-like-button-renderer"), target],
            optionalAction: false,
            returnValue: [],
          },
        }),
      );
      return { ok: true };
    } catch (e) {
      return { ok: false, error: String(e && e.message ? e.message : e) };
    }
  };

  window.__YTMD_API__.playVideo = async function (args) {
    var videoId = args && args.videoId;
    if (!videoId) throw new Error("videoId required");
    var playlistId = args.playlistId || null;
    navigateWatch(videoId, playlistId);
    var loaded = tryLoadVideo(videoId, playlistId);
    // Give navigate a moment, then force play
    setTimeout(function () {
      tryLoadVideo(videoId, playlistId);
      var api = playerApi();
      if (api) {
        try {
          api.playVideo();
        } catch (e) {}
      }
    }, 400);
    return { ok: true, loaded: loaded };
  };

  window.__YTMD_API__.playPlaylist = async function (args) {
    var playlistId = args && args.playlistId;
    if (!playlistId) throw new Error("playlistId required");
    // Prefer watchEndpoint with playlistId (more reliable than watchPlaylistEndpoint alone)
    document.dispatchEvent(
      new CustomEvent("yt-navigate", {
        bubbles: true,
        cancelable: true,
        composed: true,
        detail: {
          endpoint: {
            watchEndpoint: { playlistId: playlistId },
          },
        },
      }),
    );
    document.dispatchEvent(
      new CustomEvent("yt-navigate", {
        bubbles: true,
        cancelable: true,
        composed: true,
        detail: {
          endpoint: {
            watchPlaylistEndpoint: { playlistId: playlistId },
          },
        },
      }),
    );
    setTimeout(function () {
      var api = playerApi();
      if (api) {
        try {
          api.playVideo();
        } catch (e) {}
      }
    }, 500);
    return { ok: true };
  };

  window.__YTMD_API__.openBrowse = async function (args) {
    if (!args || !args.browseId) throw new Error("browseId required");
    navigateBrowse(args.browseId, args.params || null);
    return { ok: true };
  };

  window.__YTMD_API__.playQueueIndex = async function (args) {
    var index = Number(args && args.index);
    if (isNaN(index)) throw new Error("index required");
    var hook = window.__YTMD_HOOK__;
    if (!hook || !hook.ytmStore) throw new Error("store not ready");
    var state = hook.ytmStore.getState();
    var queue = state.queue;
    var maxQueueIndex = state.queue.items.length - 1;
    var useAutoMix = false;
    if (index > maxQueueIndex) {
      index = index - state.queue.items.length;
      useAutoMix = true;
    }
    var song = useAutoMix ? queue.automixItems[index] : queue.items[index];
    if (!song) throw new Error("queue index out of range");
    var renderer =
      song.playlistPanelVideoRenderer ||
      (song.playlistPanelVideoWrapperRenderer &&
        song.playlistPanelVideoWrapperRenderer.primaryRenderer &&
        song.playlistPanelVideoWrapperRenderer.primaryRenderer.playlistPanelVideoRenderer);
    if (!renderer || !renderer.navigationEndpoint) throw new Error("missing endpoint");
    var watch = renderer.navigationEndpoint.watchEndpoint;
    document.dispatchEvent(
      new CustomEvent("yt-navigate", {
        bubbles: true,
        cancelable: true,
        composed: true,
        detail: { endpoint: { watchEndpoint: watch } },
      }),
    );
    if (watch && watch.videoId) tryLoadVideo(watch.videoId, watch.playlistId || null);
    return { ok: true };
  };
})();
