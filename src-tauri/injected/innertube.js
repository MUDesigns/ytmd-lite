// Innertube browse/search helpers + auth probe (runs in YTM page context).
(function () {
  function getCfg() {
    var out = {
      apiKey: null,
      clientName: "WEB_REMIX",
      clientVersion: "1.20250317.01.00",
      visitorData: null,
      hl: "en",
      gl: "US",
    };
    try {
      if (window.ytcfg && typeof window.ytcfg.get === "function") {
        out.apiKey = window.ytcfg.get("INNERTUBE_API_KEY") || out.apiKey;
        var cn = window.ytcfg.get("INNERTUBE_CLIENT_NAME");
        if (cn && typeof cn !== "number") out.clientName = cn;
        out.clientVersion = window.ytcfg.get("INNERTUBE_CLIENT_VERSION") || out.clientVersion;
        out.visitorData = window.ytcfg.get("VISITOR_DATA") || out.visitorData;
        out.hl = window.ytcfg.get("HL") || out.hl;
        out.gl = window.ytcfg.get("GL") || out.gl;
      }
    } catch (e) {}
    if (!out.apiKey) {
      try {
        var scripts = document.querySelectorAll("script");
        for (var i = 0; i < scripts.length; i++) {
          var t = scripts[i].textContent || "";
          if (!out.apiKey) {
            var m = t.match(/"INNERTUBE_API_KEY"\s*:\s*"([^"]+)"/);
            if (m) out.apiKey = m[1];
          }
          if (!out.visitorData) {
            var vd = t.match(/"VISITOR_DATA"\s*:\s*"([^"]+)"/);
            if (vd) out.visitorData = vd[1];
          }
          if (out.clientVersion === "1.20250317.01.00") {
            var cv = t.match(/"INNERTUBE_CLIENT_VERSION"\s*:\s*"([^"]+)"/);
            if (cv) out.clientVersion = cv[1];
          }
          if (out.apiKey && out.visitorData) break;
        }
      } catch (e2) {}
    }
    return out;
  }

  function cookieValue(name) {
    try {
      var parts = (document.cookie || "").split(";");
      for (var i = 0; i < parts.length; i++) {
        var p = parts[i].trim();
        if (p.indexOf(name + "=") === 0) {
          return decodeURIComponent(p.slice(name.length + 1));
        }
      }
    } catch (e) {}
    return null;
  }

  async function sha1Hex(message) {
    var data = new TextEncoder().encode(message);
    var digest = await crypto.subtle.digest("SHA-1", data);
    return Array.prototype.map
      .call(new Uint8Array(digest), function (b) {
        return ("0" + b.toString(16)).slice(-2);
      })
      .join("");
  }

  async function buildAuthHeaders() {
    var origin = "https://music.youtube.com";
    var headers = {
      "Content-Type": "application/json",
      Origin: origin,
      Referer: origin + "/",
      "X-Origin": origin,
      "X-Goog-AuthUser": "0",
    };
    var injected = window.__YTMD_AUTH_HEADERS__;
    if (injected && injected.Authorization) {
      for (var k in injected) {
        if (Object.prototype.hasOwnProperty.call(injected, k) && injected[k]) {
          headers[k] = injected[k];
        }
      }
      return headers;
    }
    var sapisid =
      cookieValue("SAPISID") ||
      cookieValue("__Secure-3PAPISID") ||
      cookieValue("__Secure-1PAPISID");
    if (sapisid && window.crypto && window.crypto.subtle) {
      var ts = Math.floor(Date.now() / 1000).toString();
      var hash = await sha1Hex(ts + " " + sapisid + " " + origin);
      headers.Authorization = "SAPISIDHASH " + ts + "_" + hash;
    }
    return headers;
  }

  async function innertube(endpoint, body) {
    var cfg = getCfg();
    if (!cfg.apiKey) {
      throw new Error(
        "INNERTUBE_API_KEY not found — open YouTube Music (sign in) and wait for the page to finish loading",
      );
    }
    var context = {
      client: {
        clientName: "WEB_REMIX",
        clientVersion: cfg.clientVersion || "1.20250317.01.00",
        hl: cfg.hl || "en",
        gl: cfg.gl || "US",
      },
      user: { lockedSafetyMode: false },
    };
    if (cfg.visitorData) context.client.visitorData = cfg.visitorData;
    var payload = Object.assign({ context: context }, body || {});
    var url =
      "https://music.youtube.com/youtubei/v1/" +
      endpoint +
      "?prettyPrint=false&key=" +
      encodeURIComponent(cfg.apiKey);
    var headers = await buildAuthHeaders();
    if (cfg.visitorData) headers["X-Goog-Visitor-Id"] = cfg.visitorData;
    var ctrl = typeof AbortController !== "undefined" ? new AbortController() : null;
    var timer =
      ctrl &&
      setTimeout(function () {
        try {
          ctrl.abort();
        } catch (e) {}
      }, 20000);
    try {
      var res = await fetch(url, {
        method: "POST",
        credentials: "include",
        signal: ctrl ? ctrl.signal : undefined,
        headers: headers,
        body: JSON.stringify(payload),
      });
      if (!res.ok) throw new Error("Innertube " + endpoint + " HTTP " + res.status);
      return await res.json();
    } catch (e) {
      if (e && e.name === "AbortError") {
        throw new Error("Innertube " + endpoint + " timed out");
      }
      throw e;
    } finally {
      if (timer) clearTimeout(timer);
    }
  }

  function thumbUrls(thumbs) {
    if (!thumbs || !thumbs.length) return [];
    return thumbs
      .map(function (t) {
        return t.url || (t.thumbnails && t.thumbnails[0] && t.thumbnails[0].url) || "";
      })
      .filter(Boolean);
  }

  function fromMusicResponsiveListItemRenderer(r) {
    if (!r) return null;
    var flex = r.flexColumns || [];
    var titleRun =
      flex[0] &&
      flex[0].musicResponsiveListItemFlexColumnRenderer &&
      flex[0].musicResponsiveListItemFlexColumnRenderer.text &&
      flex[0].musicResponsiveListItemFlexColumnRenderer.text.runs &&
      flex[0].musicResponsiveListItemFlexColumnRenderer.text.runs[0];
    var subtitleRuns =
      flex[1] &&
      flex[1].musicResponsiveListItemFlexColumnRenderer &&
      flex[1].musicResponsiveListItemFlexColumnRenderer.text &&
      flex[1].musicResponsiveListItemFlexColumnRenderer.text.runs;
    var title = (titleRun && titleRun.text) || "";
    var subtitle = subtitleRuns
      ? subtitleRuns
          .map(function (x) {
            return x.text;
          })
          .join("")
      : "";
    var thumbs =
      (r.thumbnail &&
        r.thumbnail.musicThumbnailRenderer &&
        r.thumbnail.musicThumbnailRenderer.thumbnail &&
        r.thumbnail.musicThumbnailRenderer.thumbnail.thumbnails) ||
      [];
    var videoId = null;
    var browseId = null;
    var playlistId = null;
    var type = "song";
    var nav = r.navigationEndpoint || {};
    if (nav.watchEndpoint) {
      videoId = nav.watchEndpoint.videoId;
      playlistId = nav.watchEndpoint.playlistId || null;
      type = "song";
    } else if (nav.browseEndpoint) {
      browseId = nav.browseEndpoint.browseId;
      var pageType =
        (nav.browseEndpoint.browseEndpointContextSupportedConfigs &&
          nav.browseEndpoint.browseEndpointContextSupportedConfigs
            .browseEndpointContextMusicConfig &&
          nav.browseEndpoint.browseEndpointContextSupportedConfigs
            .browseEndpointContextMusicConfig.pageType) ||
        "";
      if (pageType.indexOf("ARTIST") >= 0) type = "artist";
      else if (pageType.indexOf("ALBUM") >= 0) type = "album";
      else if (pageType.indexOf("PLAYLIST") >= 0 || (browseId && browseId.indexOf("VL") === 0))
        type = "playlist";
      else type = "album";
      if (browseId && browseId.indexOf("VL") === 0) playlistId = browseId.slice(2);
    }
    // overlay play button
    try {
      var overlay =
        r.overlay &&
        r.overlay.musicItemThumbnailOverlayRenderer &&
        r.overlay.musicItemThumbnailOverlayRenderer.content &&
        r.overlay.musicItemThumbnailOverlayRenderer.content.musicPlayButtonRenderer &&
        r.overlay.musicItemThumbnailOverlayRenderer.content.musicPlayButtonRenderer
          .playNavigationEndpoint;
      if (overlay && overlay.watchEndpoint) {
        videoId = videoId || overlay.watchEndpoint.videoId;
        playlistId = playlistId || overlay.watchEndpoint.playlistId;
      }
      if (overlay && overlay.watchPlaylistEndpoint) {
        playlistId = playlistId || overlay.watchPlaylistEndpoint.playlistId;
      }
    } catch (e) {}

    if (!title && !videoId && !browseId) return null;
    return {
      type: type,
      id: videoId || browseId || playlistId || title,
      title: title,
      subtitle: subtitle || undefined,
      thumbnails: thumbs.map(function (t) {
        return t.url;
      }),
      browseId: browseId || undefined,
      playlistId: playlistId || undefined,
      videoId: videoId || undefined,
    };
  }

  function fromMusicTwoRowItemRenderer(r) {
    if (!r) return null;
    var title =
      (r.title &&
        r.title.runs &&
        r.title.runs
          .map(function (x) {
            return x.text;
          })
          .join("")) ||
      "";
    var subtitle =
      (r.subtitle &&
        r.subtitle.runs &&
        r.subtitle.runs
          .map(function (x) {
            return x.text;
          })
          .join("")) ||
      "";
    var thumbs =
      (r.thumbnailRenderer &&
        r.thumbnailRenderer.musicThumbnailRenderer &&
        r.thumbnailRenderer.musicThumbnailRenderer.thumbnail &&
        r.thumbnailRenderer.musicThumbnailRenderer.thumbnail.thumbnails) ||
      [];
    var nav = r.navigationEndpoint || {};
    var videoId = nav.watchEndpoint && nav.watchEndpoint.videoId;
    var browseId = nav.browseEndpoint && nav.browseEndpoint.browseId;
    var playlistId =
      (nav.watchEndpoint && nav.watchEndpoint.playlistId) ||
      (browseId && browseId.indexOf("VL") === 0 ? browseId.slice(2) : null);
    var type = "album";
    if (videoId) type = "song";
    else if (browseId) {
      var pageType =
        (nav.browseEndpoint.browseEndpointContextSupportedConfigs &&
          nav.browseEndpoint.browseEndpointContextSupportedConfigs
            .browseEndpointContextMusicConfig &&
          nav.browseEndpoint.browseEndpointContextSupportedConfigs
            .browseEndpointContextMusicConfig.pageType) ||
        "";
      if (pageType.indexOf("ARTIST") >= 0) type = "artist";
      else if (pageType.indexOf("PLAYLIST") >= 0 || (browseId && browseId.indexOf("VL") === 0))
        type = "playlist";
      else type = "album";
    }
    return {
      type: type,
      id: videoId || browseId || title,
      title: title,
      subtitle: subtitle || undefined,
      thumbnails: thumbs.map(function (t) {
        return t.url;
      }),
      browseId: browseId || undefined,
      playlistId: playlistId || undefined,
      videoId: videoId || undefined,
    };
  }

  function fromMusicNavigationButtonRenderer(r) {
    if (!r) return null;
    var title =
      (r.buttonText &&
        r.buttonText.runs &&
        r.buttonText.runs
          .map(function (x) {
            return x.text;
          })
          .join("")) ||
      "";
    var ep =
      (r.clickCommand && r.clickCommand.browseEndpoint) ||
      (r.navigationEndpoint && r.navigationEndpoint.browseEndpoint) ||
      null;
    var browseId = ep && ep.browseId;
    var params = ep && ep.params;
    if (!title && !browseId) return null;
    // Moods/genres use a solid stripe color instead of thumbnails
    var color = null;
    try {
      if (r.solid && typeof r.solid.leftStripeColor === "number") {
        var n = r.solid.leftStripeColor >>> 0;
        var rgb = n & 0xffffff;
        color = "#" + ("000000" + rgb.toString(16)).slice(-6);
      }
    } catch (e) {}
    var thumbs = [];
    try {
      var icon =
        (r.iconImage && r.iconImage.thumbnails) ||
        (r.backgroundImage && r.backgroundImage.thumbnails) ||
        [];
      thumbs = icon.map(function (t) {
        return t.url;
      });
    } catch (e2) {}
    return {
      type: "mood",
      id: browseId || params || title,
      title: title || "Category",
      subtitle: "Mood & genre",
      thumbnails: thumbs,
      browseId: browseId || undefined,
      params: params || undefined,
      color: color || undefined,
    };
  }

  function itemFromContent(c) {
    if (!c) return null;
    return (
      fromMusicResponsiveListItemRenderer(c.musicResponsiveListItemRenderer) ||
      fromMusicTwoRowItemRenderer(c.musicTwoRowItemRenderer) ||
      fromMusicNavigationButtonRenderer(c.musicNavigationButtonRenderer) ||
      fromMusicResponsiveListItemRenderer(c.musicMultiRowListItemRenderer)
    );
  }

  function parseShelf(shelf) {
    var title =
      (shelf.title &&
        shelf.title.runs &&
        shelf.title.runs
          .map(function (x) {
            return x.text;
          })
          .join("")) ||
      (shelf.header &&
        shelf.header.musicCarouselShelfBasicHeaderRenderer &&
        shelf.header.musicCarouselShelfBasicHeaderRenderer.title &&
        shelf.header.musicCarouselShelfBasicHeaderRenderer.title.runs &&
        shelf.header.musicCarouselShelfBasicHeaderRenderer.title.runs
          .map(function (x) {
            return x.text;
          })
          .join("")) ||
      "Shelf";
    var contents = shelf.contents || shelf.items || [];
    var items = [];
    for (var i = 0; i < contents.length; i++) {
      var item = itemFromContent(contents[i]);
      if (item) items.push(item);
      // Nested grid inside a shelf (moods & genres)
      var nested = contents[i] && contents[i].gridRenderer && contents[i].gridRenderer.items;
      if (nested && nested.length) {
        for (var j = 0; j < nested.length; j++) {
          var nestedItem = itemFromContent(nested[j]);
          if (nestedItem) items.push(nestedItem);
        }
      }
    }
    return { type: "shelf", id: title, title: title, items: items, thumbnails: [] };
  }

  function walkContents(contents, shelves, flat) {
    if (!contents) return;
    for (var i = 0; i < contents.length; i++) {
      var c = contents[i];
      if (c.musicCarouselShelfRenderer) {
        var s = parseShelf(c.musicCarouselShelfRenderer);
        if (s.items.length) {
          shelves.push(s);
          flat.push.apply(flat, s.items);
        }
      } else if (c.musicImmersiveCarouselShelfRenderer) {
        var sImm = parseShelf(c.musicImmersiveCarouselShelfRenderer);
        if (sImm.items.length) {
          shelves.push(sImm);
          flat.push.apply(flat, sImm.items);
        }
      } else if (c.musicShelfRenderer) {
        var s2 = parseShelf(c.musicShelfRenderer);
        if (s2.items.length) {
          shelves.push(s2);
          flat.push.apply(flat, s2.items);
        }
      } else if (c.musicPlaylistShelfRenderer) {
        var sPl = parseShelf(c.musicPlaylistShelfRenderer);
        if (!sPl.title || sPl.title === "Shelf") sPl.title = "Songs";
        if (sPl.items.length) {
          shelves.push(sPl);
          flat.push.apply(flat, sPl.items);
        }
      } else if (c.musicCardShelfRenderer) {
        var card = c.musicCardShelfRenderer;
        var cardItem =
          fromMusicResponsiveListItemRenderer(card) ||
          (card.title
            ? {
                type: "song",
                id: (card.onTap && card.onTap.watchEndpoint && card.onTap.watchEndpoint.videoId) || card.title,
                title:
                  (card.title.runs &&
                    card.title.runs
                      .map(function (x) {
                        return x.text;
                      })
                      .join("")) ||
                  "",
                thumbnails: [],
              }
            : null);
        // Also contents under card shelf
        if (card.contents) walkContents(card.contents, shelves, flat);
        if (cardItem && cardItem.title) flat.push(cardItem);
      } else if (c.sectionListRenderer && c.sectionListRenderer.contents) {
        walkContents(c.sectionListRenderer.contents, shelves, flat);
      } else if (c.itemSectionRenderer && c.itemSectionRenderer.contents) {
        walkContents(c.itemSectionRenderer.contents, shelves, flat);
      } else if (c.gridRenderer && c.gridRenderer.items) {
        var items = [];
        for (var j = 0; j < c.gridRenderer.items.length; j++) {
          var it = itemFromContent(c.gridRenderer.items[j]);
          if (it) items.push(it);
        }
        if (items.length) {
          shelves.push({ type: "shelf", id: "grid", title: "Grid", items: items, thumbnails: [] });
          flat.push.apply(flat, items);
        }
      } else {
        var one = itemFromContent(c);
        if (one) flat.push(one);
      }
    }
  }

  function normalizeBrowse(data) {
    var shelves = [];
    var items = [];
    var contents =
      (data &&
        data.contents &&
        data.contents.singleColumnBrowseResultsRenderer &&
        data.contents.singleColumnBrowseResultsRenderer.tabs &&
        data.contents.singleColumnBrowseResultsRenderer.tabs[0] &&
        data.contents.singleColumnBrowseResultsRenderer.tabs[0].tabRenderer &&
        data.contents.singleColumnBrowseResultsRenderer.tabs[0].tabRenderer.content &&
        data.contents.singleColumnBrowseResultsRenderer.tabs[0].tabRenderer.content
          .sectionListRenderer &&
        data.contents.singleColumnBrowseResultsRenderer.tabs[0].tabRenderer.content
          .sectionListRenderer.contents) ||
      (data &&
        data.contents &&
        data.contents.twoColumnBrowseResultsRenderer &&
        data.contents.twoColumnBrowseResultsRenderer.secondaryContents &&
        data.contents.twoColumnBrowseResultsRenderer.secondaryContents.sectionListRenderer &&
        data.contents.twoColumnBrowseResultsRenderer.secondaryContents.sectionListRenderer
          .contents) ||
      [];
    walkContents(contents, shelves, items);

    // Playlist/album pages sometimes put the track list only under musicPlaylistShelfRenderer
    // already walked; also try tab content on two-column primary
    try {
      var tabs =
        data &&
        data.contents &&
        data.contents.twoColumnBrowseResultsRenderer &&
        data.contents.twoColumnBrowseResultsRenderer.tabs;
      if (tabs && tabs.length) {
        for (var ti = 0; ti < tabs.length; ti++) {
          var tabContent =
            tabs[ti].tabRenderer &&
            tabs[ti].tabRenderer.content &&
            tabs[ti].tabRenderer.content.sectionListRenderer &&
            tabs[ti].tabRenderer.content.sectionListRenderer.contents;
          if (tabContent) walkContents(tabContent, shelves, items);
        }
      }
    } catch (eTab) {}

    var headerTitle = "";
    try {
      var h = data.header;
      if (h && h.musicDetailHeaderRenderer && h.musicDetailHeaderRenderer.title) {
        headerTitle = h.musicDetailHeaderRenderer.title.runs
          .map(function (x) {
            return x.text;
          })
          .join("");
      } else if (
        h &&
        h.musicEditablePlaylistDetailHeaderRenderer &&
        h.musicEditablePlaylistDetailHeaderRenderer.header &&
        h.musicEditablePlaylistDetailHeaderRenderer.header.musicDetailHeaderRenderer &&
        h.musicEditablePlaylistDetailHeaderRenderer.header.musicDetailHeaderRenderer.title
      ) {
        headerTitle =
          h.musicEditablePlaylistDetailHeaderRenderer.header.musicDetailHeaderRenderer.title.runs
            .map(function (x) {
              return x.text;
            })
            .join("");
      } else if (h && h.musicImmersiveHeaderRenderer && h.musicImmersiveHeaderRenderer.title) {
        headerTitle = h.musicImmersiveHeaderRenderer.title.runs
          .map(function (x) {
            return x.text;
          })
          .join("");
      } else if (
        h &&
        h.musicVisualHeaderRenderer &&
        h.musicVisualHeaderRenderer.title
      ) {
        headerTitle = h.musicVisualHeaderRenderer.title.runs
          .map(function (x) {
            return x.text;
          })
          .join("");
      }
    } catch (e) {}

    // Deduplicate shelves by title+first item
    var seen = {};
    var uniq = [];
    for (var i = 0; i < shelves.length; i++) {
      var key = shelves[i].title + ":" + (shelves[i].items[0] && shelves[i].items[0].id);
      if (seen[key]) continue;
      seen[key] = true;
      uniq.push(shelves[i]);
    }

    return { title: headerTitle || null, shelves: uniq, items: items };
  }

  function normalizeSearch(data) {
    var shelves = [];
    var items = [];
    var contents =
      (data &&
        data.contents &&
        data.contents.tabbedSearchResultsRenderer &&
        data.contents.tabbedSearchResultsRenderer.tabs &&
        data.contents.tabbedSearchResultsRenderer.tabs[0] &&
        data.contents.tabbedSearchResultsRenderer.tabs[0].tabRenderer &&
        data.contents.tabbedSearchResultsRenderer.tabs[0].tabRenderer.content &&
        data.contents.tabbedSearchResultsRenderer.tabs[0].tabRenderer.content
          .sectionListRenderer &&
        data.contents.tabbedSearchResultsRenderer.tabs[0].tabRenderer.content.sectionListRenderer
          .contents) ||
      [];
    walkContents(contents, shelves, items);
    return { shelves: shelves, items: items };
  }

  function isSignedIn() {
    try {
      try {
        if (window.ytcfg && typeof window.ytcfg.get === "function") {
          var logged = window.ytcfg.get("LOGGED_IN");
          if (logged === 1 || logged === true || logged === "1") return true;
        }
      } catch (e0) {}
      if (window.__YTMD_AUTH_HEADERS__ && window.__YTMD_AUTH_HEADERS__.Authorization) {
        return true;
      }
      var chips = document.cookie || "";
      var signIn = document.querySelector(
        'a[href*="ServiceLogin"], ytmusic-guide-signin-promo-renderer',
      );
      if (signIn) return false;
      var avatar = document.querySelector(
        "#right-content img, ytmusic-settings-button img, button[aria-label*='Account'] img",
      );
      if (avatar) return true;
      if (
        chips.indexOf("SAPISID=") >= 0 ||
        chips.indexOf("__Secure-3PAPISID=") >= 0
      ) {
        return true;
      }
      return false;
    } catch (e) {
      return false;
    }
  }

  window.__YTMD_API__ = window.__YTMD_API__ || {};

  window.__YTMD_API__.probeAuth = async function () {
    var signedIn = isSignedIn();
    var headers = await buildAuthHeaders();
    return {
      signedIn: signedIn,
      ready: true,
      hasAuthHeader: !!(headers && headers.Authorization),
    };
  };

  window.__YTMD_API__.browse = async function (args) {
    var browseId = (args && args.browseId) || "FEmusic_home";
    var body = { browseId: browseId };
    if (args && args.params) body.params = args.params;
    var data = await innertube("browse", body);
    return normalizeBrowse(data);
  };

  window.__YTMD_API__.search = async function (args) {
    var query = (args && args.query) || "";
    if (!query) return { shelves: [], items: [] };
    var data = await innertube("search", { query: query });
    return normalizeSearch(data);
  };

  window.__YTMD_API__.getSearchSuggestions = async function (args) {
    var query = (args && args.query) || "";
    if (!query) return { suggestions: [] };
    var data = await innertube("music/get_search_suggestions", { input: query });
    var suggestions = [];
    try {
      var contents = (data && data.contents) || [];
      for (var i = 0; i < contents.length; i++) {
        var s =
          contents[i].searchSuggestionsSectionRenderer &&
          contents[i].searchSuggestionsSectionRenderer.contents;
        if (!s) continue;
        for (var j = 0; j < s.length; j++) {
          var r =
            s[j].searchSuggestionRenderer ||
            s[j].musicResponsiveListItemRenderer;
          if (!r) continue;
          var text =
            (r.suggestion &&
              r.suggestion.runs &&
              r.suggestion.runs
                .map(function (x) {
                  return x.text;
                })
                .join("")) ||
            (r.text &&
              r.text.runs &&
              r.text.runs
                .map(function (x) {
                  return x.text;
                })
                .join("")) ||
            "";
          if (text) suggestions.push(text);
        }
      }
    } catch (e) {}
    return { suggestions: suggestions };
  };

  window.__YTMD_API__.getHome = async function () {
    return window.__YTMD_API__.browse({ browseId: "FEmusic_home" });
  };

  window.__YTMD_API__.getExplore = async function () {
    return window.__YTMD_API__.browse({ browseId: "FEmusic_explore" });
  };

  window.__YTMD_API__.getLibrary = async function () {
    // Merge several library surfaces so playlists / likes show up when auth works.
    var ids = [
      "FEmusic_library_landing",
      "FEmusic_liked_playlists",
      "FEmusic_liked_albums",
      "FEmusic_library_corpus_track_artists",
    ];
    var shelves = [];
    var items = [];
    var title = "Library";
    for (var i = 0; i < ids.length; i++) {
      try {
        var part = await window.__YTMD_API__.browse({ browseId: ids[i] });
        if (part && part.title && i === 0) title = part.title;
        if (part && part.shelves && part.shelves.length) {
          for (var s = 0; s < part.shelves.length; s++) {
            var shelf = part.shelves[s];
            if (shelf && shelf.items && shelf.items.length) shelves.push(shelf);
          }
        }
        if (part && part.items && part.items.length) {
          items.push.apply(items, part.items);
        }
      } catch (e) {
        /* skip missing / unauthorized surfaces */
      }
    }
    if (!shelves.length && items.length) {
      shelves.push({
        type: "shelf",
        id: "library-items",
        title: "Library",
        items: items,
        thumbnails: [],
      });
    }
    return { title: title, shelves: shelves, items: items };
  };

  // Emit auth status periodically for the host (once per page)
  function pushAuth() {
    try {
      if (window.ytmd && window.ytmd.emitData) {
        window.ytmd.emitData("auth", { signedIn: isSignedIn(), ready: true });
      }
    } catch (e) {}
  }
  if (!window.__YTMD_AUTH_TIMER__) {
    window.__YTMD_AUTH_TIMER__ = setInterval(pushAuth, 5000);
    setTimeout(pushAuth, 1500);
  }
})();
