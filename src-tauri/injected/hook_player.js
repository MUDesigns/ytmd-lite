// Hook YouTube Music playerApi + store; emit normalized state for Last.fm + UI.
(function () {
  function mapVideoState(ytState) {
    if (ytState === 1) return "Playing";
    if (ytState === 2) return "Paused";
    if (ytState === 3) return "Buffering";
    return "Unknown";
  }

  function getTextRuns(runs) {
    if (!runs || !runs.length) return "";
    return runs
      .map(function (r) {
        return r.text || "";
      })
      .join("");
  }

  function extractRelatedIds(queue, selectedIndex) {
    var ids = [];
    try {
      if (!queue || !queue.items || !queue.items.length) return ids;
      var item = queue.items[selectedIndex];
      if (!item) return ids;
      var renderer =
        item.playlistPanelVideoRenderer ||
        (item.playlistPanelVideoWrapperRenderer &&
          item.playlistPanelVideoWrapperRenderer.primaryRenderer &&
          item.playlistPanelVideoWrapperRenderer.primaryRenderer.playlistPanelVideoRenderer);
      if (renderer && renderer.videoId) ids.push(renderer.videoId);
      var counterparts =
        item.playlistPanelVideoWrapperRenderer &&
        item.playlistPanelVideoWrapperRenderer.counterpart;
      if (counterparts && counterparts.length) {
        counterparts.forEach(function (c) {
          var id =
            c.counterpartRenderer &&
            c.counterpartRenderer.playlistPanelVideoRenderer &&
            c.counterpartRenderer.playlistPanelVideoRenderer.videoId;
          if (id) ids.push(id);
        });
      }
    } catch (e) {}
    return ids;
  }

  function queueItemFrom(it) {
    var r =
      it.playlistPanelVideoRenderer ||
      (it.playlistPanelVideoWrapperRenderer &&
        it.playlistPanelVideoWrapperRenderer.primaryRenderer &&
        it.playlistPanelVideoWrapperRenderer.primaryRenderer.playlistPanelVideoRenderer);
    if (!r) return null;
    var thumbs =
      (r.thumbnail && r.thumbnail.thumbnails) ||
      (r.thumbnailRenderer &&
        r.thumbnailRenderer.musicThumbnailRenderer &&
        r.thumbnailRenderer.musicThumbnailRenderer.thumbnail &&
        r.thumbnailRenderer.musicThumbnailRenderer.thumbnail.thumbnails) ||
      [];
    return {
      videoId: r.videoId || "",
      title: getTextRuns(r.title && r.title.runs),
      author: getTextRuns(r.shortBylineText && r.shortBylineText.runs),
      thumbnails: thumbs.map(function (t) {
        return t.url;
      }),
      selected: !!r.selected,
    };
  }

  function waitForReady(cb) {
    var tries = 0;
    var timer = setInterval(function () {
      tries++;
      try {
        if (
          window.__YTMD_HOOK__ &&
          document.querySelector("ytmusic-app-layout>ytmusic-player-bar") &&
          document.querySelector("ytmusic-app-layout>ytmusic-player-bar").playerApi &&
          document.querySelector("ytmusic-app-layout>ytmusic-player-bar").playerApi.isReady()
        ) {
          clearInterval(timer);
          cb();
        }
      } catch (e) {}
      if (tries > 240) clearInterval(timer);
    }, 250);
  }

  waitForReady(function () {
    var ytmStore = window.__YTMD_HOOK__.ytmStore;
    var bar = document.querySelector("ytmusic-app-layout>ytmusic-player-bar");
    var playerApi = bar.playerApi;
    var lastEmitKey = "";

    function buildAndEmit() {
      try {
        var storeState = ytmStore.getState();
        var response = playerApi.getPlayerResponse();
        var videoDetailsRaw = response && response.videoDetails;
        if (!videoDetailsRaw) return;

        var album = "";
        var albumId = "";
        var title = videoDetailsRaw.title || "";
        var currentItem = bar.currentItem;
        if (currentItem) {
          title = getTextRuns(currentItem.title && currentItem.title.runs) || title;
          if (currentItem.longBylineText && currentItem.longBylineText.runs) {
            for (var i = 0; i < currentItem.longBylineText.runs.length; i++) {
              var item = currentItem.longBylineText.runs[i];
              if (
                item.navigationEndpoint &&
                item.navigationEndpoint.browseEndpoint &&
                item.navigationEndpoint.browseEndpoint.browseEndpointContextSupportedConfigs &&
                item.navigationEndpoint.browseEndpoint.browseEndpointContextSupportedConfigs
                  .browseEndpointContextMusicConfig &&
                item.navigationEndpoint.browseEndpoint.browseEndpointContextSupportedConfigs
                  .browseEndpointContextMusicConfig.pageType === "MUSIC_PAGE_TYPE_ALBUM"
              ) {
                album = item.text || "";
                albumId = item.navigationEndpoint.browseEndpoint.browseId || "";
              }
            }
          }
        }

        var queue = storeState.queue || {};
        var items = queue.items || [];
        var selectedIndex = 0;
        var queueOut = [];
        for (var qi = 0; qi < items.length; qi++) {
          var mapped = queueItemFrom(items[qi]);
          if (mapped) {
            if (mapped.selected) selectedIndex = qi;
            queueOut.push(mapped);
          }
        }

        var ytState = playerApi.getPlayerState ? playerApi.getPlayerState() : -1;
        if (typeof window.__YTMD_LITE_TRACK_STATE__ === "number") {
          ytState = window.__YTMD_LITE_TRACK_STATE__;
        }

        var thumbs = [];
        try {
          if (videoDetailsRaw.thumbnail && videoDetailsRaw.thumbnail.thumbnails) {
            thumbs = videoDetailsRaw.thumbnail.thumbnails.map(function (t) {
              return t.url;
            });
          }
        } catch (e) {}

        var volume = 100;
        var muted = false;
        try {
          if (typeof playerApi.getVolume === "function") volume = playerApi.getVolume() || 0;
          if (typeof playerApi.isMuted === "function") muted = !!playerApi.isMuted();
        } catch (e2) {}

        var likeStatus = "INDIFFERENT";
        try {
          var vid = videoDetailsRaw.videoId;
          if (storeState.likeStatus && storeState.likeStatus.videos && storeState.likeStatus.videos[vid]) {
            likeStatus = storeState.likeStatus.videos[vid];
          } else {
            var likeEl = bar.querySelector("ytmusic-like-button-renderer");
            if (likeEl && likeEl.data && likeEl.data.likeStatus) likeStatus = likeEl.data.likeStatus;
          }
        } catch (e3) {}

        var payload = {
          videoDetails: {
            id: videoDetailsRaw.videoId || "",
            title: title,
            author: videoDetailsRaw.author || "",
            album: album,
            albumId: albumId,
            channelId: videoDetailsRaw.channelId || "",
            durationSeconds: parseInt(videoDetailsRaw.lengthSeconds || "0", 10) || 0,
            isLive: !!videoDetailsRaw.isLive,
            thumbnails: thumbs,
          },
          trackState: mapVideoState(ytState),
          relatedVideoIds: extractRelatedIds(queue, selectedIndex),
          playlistId: playerApi.getPlaylistId ? playerApi.getPlaylistId() || "" : "",
          videoProgress: window.__YTMD_LITE_PROGRESS__ || 0,
          volume: volume,
          muted: muted,
          likeStatus: likeStatus,
          queue: queueOut,
          queueIndex: selectedIndex,
        };

        var key =
          payload.videoDetails.id +
          "|" +
          payload.trackState +
          "|" +
          payload.videoDetails.title +
          "|" +
          payload.videoDetails.durationSeconds +
          "|" +
          Math.floor(payload.videoProgress) +
          "|" +
          payload.volume +
          "|" +
          payload.likeStatus +
          "|" +
          payload.queueIndex;
        if (key === lastEmitKey) return;
        lastEmitKey = key;

        if (window.ytmd && window.ytmd.emitPlayerState) {
          window.ytmd.emitPlayerState(payload);
        }
      } catch (e) {
        console.warn("[ytmd-lite] emit failed", e);
      }
    }

    playerApi.addEventListener("onVideoProgress", function (progress) {
      window.__YTMD_LITE_PROGRESS__ = progress;
      var now = Date.now();
      if (
        !window.__YTMD_LITE_LAST_PROGRESS_EMIT__ ||
        now - window.__YTMD_LITE_LAST_PROGRESS_EMIT__ > 500
      ) {
        window.__YTMD_LITE_LAST_PROGRESS_EMIT__ = now;
        lastEmitKey = "";
        buildAndEmit();
      }
    });

    playerApi.addEventListener("onStateChange", function (state) {
      window.__YTMD_LITE_TRACK_STATE__ = state;
      lastEmitKey = "";
      buildAndEmit();
    });

    playerApi.addEventListener("onVideoDataChange", function (event) {
      if (
        event.playertype === 1 &&
        (event.type === "dataloaded" || event.type === "dataupdated")
      ) {
        lastEmitKey = "";
        buildAndEmit();
      }
    });

    ytmStore.subscribe(function () {
      buildAndEmit();
    });

    setTimeout(buildAndEmit, 500);
  });
})();
