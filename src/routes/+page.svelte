<script lang="ts">
  import { invoke } from "@tauri-apps/api/core";
  import { listen } from "@tauri-apps/api/event";
  import { onMount } from "svelte";
  import {
    browseDetail,
    getLikedIds,
    likeSong,
    loadExplore,
    loadHome,
    loadLibrary,
    loadRecentSearches,
    pushRecentSearch,
    resolveArtist,
    search,
    searchSuggestions,
    setArtistSubscribed,
    thumb,
    validateAuth,
    type SearchFilter,
  } from "$lib/api";
  import * as playerCtl from "$lib/player";
  import type {
    ArtistLink,
    BrowseResult,
    DetailTarget,
    DiscordRpcStatus,
    EngineStatus,
    LastFmStatus,
    MusicItem,
    Panel,
    PlayerState,
  } from "$lib/types";
  import PlayerDock from "$lib/ui/PlayerDock.svelte";
  import ShelfRow from "$lib/ui/ShelfRow.svelte";
  import AccentPicker from "$lib/ui/AccentPicker.svelte";
  import ArtistLinks from "$lib/ui/ArtistLinks.svelte";
  import ContextMenu, { type MenuAction } from "$lib/ui/ContextMenu.svelte";
  import { applyAccent, DEFAULT_ACCENT, normalizeAccent } from "$lib/accent";
  import { checkForAppUpdates } from "$lib/updater";

  let panel = $state<Panel>("home");
  let statusMsg = $state("Ready");
  let lastfm = $state<LastFmStatus>({
    enabled: false,
    authenticated: false,
    username: null,
    signingIn: false,
    scrobblePercent: 50,
    hasCredentials: false,
    lastError: null,
    nowPlaying: null,
    lastScrobble: null,
  });
  let discordRpc = $state<DiscordRpcStatus>({ enabled: false, connected: false, lastError: null });
  let accentColor = $state(DEFAULT_ACCENT);
  let player = $state<PlayerState>({ videoDetails: null, trackState: "Unknown", videoProgress: 0 });
  let engine = $state<EngineStatus>({ signedIn: false, authVisible: false, ready: false });
  let authProfile = $state<string | null>(null);
  let configPath = $state("");
  let busy = $state(false);
  let maximized = $state(false);
  let audioLabel = $state("System default");
  let audioDeviceId = $state("");
  let audioDevices = $state<Array<{ id: string; label: string }>>([
    { id: "", label: "System default" },
  ]);
  let audioListError = $state<string | null>(null);

  let homeData = $state<BrowseResult | null>(null);
  let exploreData = $state<BrowseResult | null>(null);
  let libraryData = $state<BrowseResult | null>(null);
  let searchQuery = $state("");
  let searchData = $state<BrowseResult | null>(null);
  let searchBusy = $state(false);
  let searchSuggestionsList = $state<string[]>([]);
  let searchFilter = $state<SearchFilter>("all");
  let recentSearches = $state<string[]>(typeof localStorage !== "undefined" ? loadRecentSearches() : []);
  let searchTimer: ReturnType<typeof setTimeout> | null = null;
  let detail = $state<DetailTarget | null>(null);
  let detailData = $state<BrowseResult | null>(null);
  let detailFrom = $state<Panel>("home");
  let detailItem = $state<MusicItem | null>(null);
  let likedIds = $state<Set<string>>(new Set());
  let subscribeBusy = $state(false);
  let ctx = $state<{ open: boolean; x: number; y: number; item: MusicItem | null }>({
    open: false,
    x: 0,
    y: 0,
    item: null,
  });
  let loadError = $state<string | null>(null);
  let loading = $state(false);

  /** Rail highlight — stays on Library when drilling into a playlist detail. */
  const railActive = $derived(panel === "detail" ? detailFrom : panel);

  const breadcrumb = $derived.by(() => {
    if (panel === "detail" && (detail?.title || detailData?.title)) {
      return `${detailFrom}/${detail?.title || detailData?.title || "detail"}`;
    }
    if (searchQuery.trim()) {
      return `search/${searchQuery.trim()}`;
    }
    return panel;
  });

  const isSearching = $derived(!!searchQuery.trim());
  const detailCover = $derived(
    thumb({ thumbnails: detailData?.thumbnails }) ||
      thumb(detailData?.items?.[0]) ||
      thumb(detailItem) ||
      "",
  );
  const detailTrackCount = $derived(detailData?.items?.filter((t) => t.videoId).length ?? 0);

  function setSignedIn(signedIn: boolean, profile: string | null = null) {
    engine = { ...engine, signedIn, ready: true, authVisible: false };
    authProfile = signedIn ? profile : null;
  }

  async function openLogin() {
    statusMsg = "Opening Google sign-in…";
    try {
      await invoke("open_login_window");
    } catch (e) {
      statusMsg = String(e);
    }
  }

  async function signOutGoogle() {
    busy = true;
    statusMsg = "Signing out…";
    try {
      await invoke("google_logout");
      setSignedIn(false);
      homeData = null;
      exploreData = null;
      libraryData = null;
      detailData = null;
      statusMsg = "Signed out";
    } catch (e) {
      statusMsg = String(e);
    } finally {
      busy = false;
    }
  }

  onMount(() => {
    const unsubs: Array<() => void> = [];

    // GitHub Releases auto-update (no-op for unsigned local builds)
    void checkForAppUpdates();

    unsubs.push(playerCtl.subscribe((s) => {
      player = s;
      if (s.videoDetails?.id) {
        const liked = likedIds.has(s.videoDetails.id);
        if ((s.likeStatus === "LIKE") !== liked) {
          playerCtl.setLikeStatus(liked ? "LIKE" : "INDIFFERENT");
        }
      }
      if (s.trackState === "Buffering" && s.videoDetails?.title) {
        statusMsg = `Buffering ${s.videoDetails.title}…`;
      } else if (s.trackState === "Playing" && s.videoDetails?.title) {
        if (statusMsg.startsWith("Buffering ") || statusMsg.startsWith("Playing ")) {
          statusMsg = `Playing ${s.videoDetails.title}`;
        }
      }
    }));

    (async () => {
      try {
        lastfm = await invoke<LastFmStatus>("get_lastfm_status");
        discordRpc = await invoke<DiscordRpcStatus>("get_discord_rpc_status");
        try {
          const saved = await invoke<string>("get_accent_color");
          accentColor = normalizeAccent(saved);
          applyAccent(accentColor);
        } catch {
          applyAccent(DEFAULT_ACCENT);
        }
        configPath = await invoke<string>("get_config_path");
        maximized = await invoke<boolean>("window_is_maximized");
        const audio = await invoke<{ id: string | null; label: string | null }>("get_audio_output");
        audioLabel = audio.label || (audio.id ? audio.id : "System default");
        audioDeviceId = audio.id || "";
        // Prefer browser sink list (real setSinkId ids). Fall back to saved id.
        try {
          audioDevices = await playerCtl.listAudioOutputs();
          if (audioDeviceId && !audioDevices.some((d) => d.id === audioDeviceId)) {
            // Saved cpal "host:…" ids are not valid — rematch by label
            const match = audioDevices.find(
              (d) => d.label === audioLabel || d.label.includes(audioLabel) || audioLabel.includes(d.label),
            );
            audioDeviceId = match?.id || "";
            if (match) audioLabel = match.label;
          }
          if (audioDeviceId || audioLabel) {
            await playerCtl.applySinkId(audioDeviceId, audioLabel);
          }
        } catch (e) {
          statusMsg = String(e);
        }
      } catch (e) {
        statusMsg = String(e);
      }

      unsubs.push(
        await listen<{ id: string; label: string }>("audio-output", (e) => {
          audioLabel = e.payload.label || e.payload.id || "System default";
          audioDeviceId = e.payload.id || "";
        }),
      );
      unsubs.push(
        await listen<{ id: string; label: string }>("audio-sink", (e) => {
          void playerCtl.applySinkId(e.payload.id || "", e.payload.label).catch((err) => {
            statusMsg = String(err);
          });
        }),
      );
      unsubs.push(
        await listen<{
          devices?: Array<{ id: string; label: string }>;
          error?: string;
        }>("audio-devices", (e) => {
          audioListError = e.payload.error ?? null;
          if (e.payload.devices?.length) audioDevices = e.payload.devices;
        }),
      );
      unsubs.push(
        await listen<LastFmStatus>("lastfm-status", (e) => {
          lastfm = e.payload;
        }),
      );
      unsubs.push(
        await listen<DiscordRpcStatus>("discord-rpc-status", (e) => {
          discordRpc = e.payload;
        }),
      );
      unsubs.push(
        await listen<string>("status-message", (e) => {
          statusMsg = e.payload;
        }),
      );
      unsubs.push(
        await listen<string>("media-command", (e) => {
          void playerCtl.handleMediaCommand(e.payload);
        }),
      );
      unsubs.push(
        await listen<string>("login-complete", async () => {
          await probeAuth();
          statusMsg = "Signed in";
          void refreshLiked();
          void refreshPanel("home");
        }),
      );
      unsubs.push(
        await listen("login-cancelled", () => {
          statusMsg = "Sign-in cancelled";
        }),
      );
      unsubs.push(
        await listen("auth-signed-out", () => {
          setSignedIn(false);
          statusMsg = "Signed out";
        }),
      );

      const onResize = () => {
        invoke("resize_ytm_webview").catch(() => {});
        invoke<boolean>("window_is_maximized")
          .then((m) => (maximized = m))
          .catch(() => {});
      };
      window.addEventListener("resize", onResize);
      unsubs.push(() => window.removeEventListener("resize", onResize));

      await probeAuth();
      if (engine.signedIn) {
        void refreshPanel("home");
        void refreshLiked();
      }
    })();

    return () => unsubs.forEach((u) => u());
  });

  async function probeAuth() {
    const auth = await validateAuth();
    setSignedIn(!!auth.valid, auth.profile ?? null);
    if (!auth.valid) {
      statusMsg =
        auth.reason === "api_unreachable"
          ? "Catalog API offline — start Kodama backend or restart the app"
          : "Sign in to load your library";
    } else {
      statusMsg = "Ready";
    }
  }

  async function refreshPanel(target: Panel = panel) {
    loading = true;
    loadError = null;
    try {
      if (target === "home") homeData = await loadHome();
      else if (target === "explore") exploreData = await loadExplore();
      else if (target === "library") libraryData = await loadLibrary();
      else if (target === "detail" && detail) {
        // Ensure like hearts match the signed-in account before painting tracks
        const [, data] = await Promise.all([refreshLiked(), browseDetail(detail)]);
        detailData = data;
      }
      if (!engine.signedIn) {
        const auth = await validateAuth();
        if (auth.valid) setSignedIn(true, auth.profile ?? null);
      }
    } catch (e) {
      const msg = String(e);
      loadError = msg;
      if (/no_profile|valid|auth|cookie|401|403/i.test(msg) || !engine.signedIn) {
        statusMsg = "Sign in required";
      }
    } finally {
      loading = false;
    }
  }

  async function setPanel(next: Panel) {
    panel = next;
    if (next !== "detail") detailFrom = next;
    statusMsg = next === "settings" ? "Settings" : `Opened ${next}`;
    if (next === "settings") {
      void probeAuth();
      void refreshAudioDevices();
    }
    if (next === "home" || next === "explore" || next === "library") {
      void refreshPanel(next);
    }
  }

  async function refreshLiked() {
    try {
      likedIds = new Set(await getLikedIds());
      const id = player.videoDetails?.id;
      if (id) playerCtl.setLikeStatus(likedIds.has(id) ? "LIKE" : "INDIFFERENT");
    } catch {
      /* ignore */
    }
  }

  function isCollection(item: MusicItem): boolean {
    if (
      item.type === "album" ||
      item.type === "playlist" ||
      item.type === "artist" ||
      item.type === "mood"
    ) {
      return true;
    }
    if (item.params || item.playlistId) return true;
    const bid = item.browseId || "";
    if (!bid) return false;
    return (
      bid.startsWith("MPRE") ||
      bid.startsWith("OLAK5") ||
      bid.startsWith("VL") ||
      bid.startsWith("UC") ||
      bid.startsWith("MPSP")
    );
  }

  async function openItem(item: MusicItem) {
    // Art / title click: open detail for collections; only bare songs play.
    if (isCollection(item)) {
      if (panel !== "detail") detailFrom = panel;
      detailItem = item;
      detail = {
        browseId: item.browseId,
        playlistId: item.playlistId,
        title: item.title,
        params: item.params,
      };
      detailData = null;
      panel = "detail";
      void refreshPanel("detail");
      return;
    }
    if (item.videoId) {
      await playFromUi(item);
    }
  }

  async function openArtist(artist: ArtistLink) {
    let browseId = artist.browseId;
    let title = artist.name;
    if (!browseId) {
      statusMsg = `Finding ${artist.name}…`;
      try {
        const resolved = await resolveArtist(artist.name);
        if (!resolved?.browseId) {
          statusMsg = `Couldn't find artist “${artist.name}”`;
          return;
        }
        browseId = resolved.browseId;
        title = resolved.name || title;
      } catch (e) {
        statusMsg = String(e);
        return;
      }
    }
    await openItem({
      type: "artist",
      id: browseId,
      title,
      browseId,
      thumbnails: [],
    });
  }

  async function toggleArtistSubscribe() {
    const browseId = detail?.browseId || detailItem?.browseId || detailData?.channelId;
    if (!browseId || detailData?.kind !== "artist" || subscribeBusy) return;
    const next = !detailData.subscribed;
    subscribeBusy = true;
    const prev = detailData.subscribed;
    detailData = { ...detailData, subscribed: next };
    try {
      await setArtistSubscribed(browseId, next, detailData.channelId);
      statusMsg = next
        ? `Subscribed to ${detailData.title || "artist"}`
        : `Unsubscribed from ${detailData.title || "artist"}`;
    } catch (e) {
      detailData = { ...detailData, subscribed: prev };
      statusMsg = String(e);
    } finally {
      subscribeBusy = false;
    }
  }

  async function resolveCollectionTracks(item: MusicItem): Promise<MusicItem[]> {
    if (item.videoId && item.type === "song") return [item];
    if (item.items?.length) return item.items.filter((t) => t.videoId);
    const data = await browseDetail({
      browseId: item.browseId,
      playlistId: item.playlistId,
      params: item.params,
      title: item.title,
    });
    return data.items.filter((t) => t.videoId);
  }

  async function playFromUi(item: MusicItem) {
    try {
      statusMsg = `Buffering ${item.title}…`;
      if (item.videoId && item.type === "song") {
        if (detailData?.items?.length) {
          const idx = detailData.items.findIndex((t) => t.videoId === item.videoId);
          if (idx >= 0) {
            await playerCtl.playItems(detailData.items, idx);
            statusMsg = `Playing ${item.title}`;
            return;
          }
        }
        await playerCtl.playItem(item);
        statusMsg = `Playing ${item.title}`;
        return;
      }
      const tracks = await resolveCollectionTracks(item);
      if (!tracks.length) throw new Error("No playable tracks");
      await playerCtl.playItems(tracks, 0);
      statusMsg = `Playing ${item.title}`;
    } catch (e) {
      statusMsg = String(e);
    }
  }

  async function shufflePlayItem(item: MusicItem | null = detailItem) {
    try {
      const target = item;
      if (!target && detailData?.items?.length) {
        await playerCtl.playItemsShuffled(detailData.items);
        statusMsg = `Shuffle · ${detailData.title || "collection"}`;
        return;
      }
      if (!target) return;
      statusMsg = `Shuffle · ${target.title}…`;
      if (target.videoId && target.type === "song") {
        await playerCtl.playItem(target);
        playerCtl.toggleShuffle();
        statusMsg = `Playing ${target.title}`;
        return;
      }
      const tracks = target === detailItem && detailData?.items?.length
        ? detailData.items
        : await resolveCollectionTracks(target);
      await playerCtl.playItemsShuffled(tracks);
      statusMsg = `Shuffle · ${target.title}`;
    } catch (e) {
      statusMsg = String(e);
    }
  }

  async function queueItem(item: MusicItem, next = false) {
    try {
      const tracks =
        item.videoId && item.type === "song"
          ? [item]
          : await resolveCollectionTracks(item);
      const n = next ? playerCtl.playNext(tracks) : playerCtl.addToQueue(tracks);
      statusMsg = n ? `${next ? "Play next" : "Queued"} · ${n} track${n === 1 ? "" : "s"}` : "Nothing to queue";
    } catch (e) {
      statusMsg = String(e);
    }
  }

  async function toggleLikeFor(videoId: string | undefined, meta?: MusicItem | null) {
    if (!videoId) return;
    const liked = likedIds.has(videoId);
    const rating = liked ? "INDIFFERENT" : "LIKE";
    try {
      await likeSong(videoId, rating, {
        title: meta?.title,
        artists: meta?.subtitle,
        thumbnail: meta?.thumbnails?.[0],
      });
      const next = new Set(likedIds);
      if (liked) next.delete(videoId);
      else next.add(videoId);
      likedIds = next;
      if (player.videoDetails?.id === videoId) {
        playerCtl.setLikeStatus(rating);
      }
      statusMsg = liked ? "Removed from likes" : "Liked";
    } catch (e) {
      statusMsg = String(e);
    }
  }

  async function toggleLikeCurrent() {
    await toggleLikeFor(player.videoDetails?.id, {
      type: "song",
      id: player.videoDetails?.id || "",
      title: player.videoDetails?.title || "",
      subtitle: player.videoDetails?.author,
      thumbnails: player.videoDetails?.thumbnails || [],
      videoId: player.videoDetails?.id,
    });
  }

  function openContext(item: MusicItem, ev: MouseEvent) {
    ctx = { open: true, x: ev.clientX, y: ev.clientY, item };
  }

  function contextActions(item: MusicItem | null): MenuAction[] {
    if (!item) return [];
    const isSong = !!(item.videoId && (item.type === "song" || !item.browseId));
    const canOpen =
      item.type === "album" ||
      item.type === "playlist" ||
      item.type === "artist" ||
      !!item.browseId ||
      !!item.playlistId ||
      !!item.params;
    const actions: MenuAction[] = [
      { id: "play", label: "Play", icon: "play_arrow" },
      { id: "shuffle", label: "Shuffle play", icon: "shuffle" },
      { id: "queue", label: "Add to queue", icon: "playlist_add" },
      { id: "next", label: "Play next", icon: "playlist_play" },
    ];
    if (canOpen) {
      actions.push({ id: "open", label: "Open", icon: "open_in_new" });
    }
    const artist =
      item.artistLinks?.find((a) => a.name) ||
      (item.artistBrowseId
        ? { name: item.subtitle || "Artist", browseId: item.artistBrowseId }
        : item.subtitle
          ? { name: item.subtitle.split(",")[0].trim() }
          : null);
    if (artist?.name) {
      actions.push({
        id: "artist",
        label: `Go to ${artist.name}`,
        icon: "person",
      });
    }
    if (isSong && item.videoId) {
      actions.push({ id: "sep1", label: "", separator: true });
      actions.push({
        id: "like",
        label: likedIds.has(item.videoId) ? "Unlike" : "Like",
        icon: likedIds.has(item.videoId) ? "heart_minus" : "favorite",
      });
    }
    return actions;
  }

  async function onContextSelect(id: string) {
    const item = ctx.item;
    ctx = { ...ctx, open: false };
    if (!item) return;
    if (id === "play") await playFromUi(item);
    else if (id === "shuffle") await shufflePlayItem(item);
    else if (id === "queue") await queueItem(item, false);
    else if (id === "next") await queueItem(item, true);
    else if (id === "open") await openItem(item);
    else if (id === "artist") {
      const artist =
        item.artistLinks?.find((a) => a.name) ||
        (item.artistBrowseId
          ? { name: item.subtitle || "Artist", browseId: item.artistBrowseId }
          : item.subtitle
            ? { name: item.subtitle.split(",")[0].trim() }
            : null);
      if (artist?.name) void openArtist(artist);
    } else if (id === "like") await toggleLikeFor(item.videoId, item);
  }

  async function playQueueIndex(index: number) {
    await playerCtl.playQueueIndex(index);
  }

  const SEARCH_FILTERS: { id: SearchFilter; label: string }[] = [
    { id: "all", label: "All" },
    { id: "songs", label: "Songs" },
    { id: "artists", label: "Artists" },
    { id: "albums", label: "Albums" },
    { id: "playlists", label: "Playlists" },
    { id: "library", label: "Library" },
  ];

  function onSearchInput() {
    if (searchTimer) clearTimeout(searchTimer);
    const q = searchQuery.trim();
    if (!q) {
      searchSuggestionsList = [];
      searchData = null;
      return;
    }
    searchTimer = setTimeout(() => {
      void runLiveSearch(q);
    }, 320);
  }

  async function runLiveSearch(q: string, filter: SearchFilter = searchFilter) {
    searchBusy = true;
    loadError = null;
    try {
      const wantSuggestions = filter !== "library";
      const [results, suggestions] = await Promise.all([
        search(q, filter),
        wantSuggestions
          ? searchSuggestions(q).catch(() => ({ suggestions: [] as string[] }))
          : Promise.resolve({ suggestions: [] as string[] }),
      ]);
      if (searchQuery.trim() !== q || searchFilter !== filter) return; // stale
      searchData = results;
      searchSuggestionsList = suggestions.suggestions ?? [];
    } catch (e) {
      loadError = String(e);
    } finally {
      searchBusy = false;
    }
  }

  async function runSearch() {
    const q = searchQuery.trim();
    if (!q) return;
    recentSearches = pushRecentSearch(q) ?? loadRecentSearches();
    await runLiveSearch(q);
  }

  function applySearchTerm(term: string) {
    searchQuery = term;
    recentSearches = pushRecentSearch(term) ?? loadRecentSearches();
    void runLiveSearch(term);
  }

  function setSearchFilter(next: SearchFilter) {
    if (searchFilter === next) return;
    searchFilter = next;
    const q = searchQuery.trim();
    if (q) void runLiveSearch(q, next);
  }

  function clearSearch() {
    searchQuery = "";
    searchData = null;
    searchSuggestionsList = [];
    if (searchTimer) clearTimeout(searchTimer);
  }

  async function minimize() {
    await invoke("window_minimize");
  }
  async function toggleMaximize() {
    maximized = await invoke<boolean>("window_toggle_maximize");
  }
  async function close() {
    await invoke("window_hide");
  }

  async function startDrag(ev: MouseEvent) {
    if (ev.buttons !== 1) return;
    const t = ev.target as HTMLElement | null;
    if (t?.closest("button, input, a, label")) return;
    try {
      await invoke("window_start_dragging");
    } catch {
      /* ignore */
    }
  }

  async function setEnabled(enabled: boolean) {
    busy = true;
    try {
      lastfm = await invoke<LastFmStatus>("set_lastfm_enabled", { enabled });
    } catch (e) {
      statusMsg = String(e);
    } finally {
      busy = false;
    }
  }

  async function setDiscordRpc(enabled: boolean) {
    busy = true;
    try {
      discordRpc = await invoke<DiscordRpcStatus>("set_discord_rpc_enabled", { enabled });
    } catch (e) {
      statusMsg = String(e);
    } finally {
      busy = false;
    }
  }

  async function onAccentChange(hex: string) {
    accentColor = hex;
    applyAccent(hex);
    try {
      await invoke("set_accent_color", { color: hex });
      statusMsg = `Accent ${hex}`;
    } catch (e) {
      statusMsg = String(e);
    }
  }

  async function setPercent(ev: Event) {
    const value = Number((ev.target as HTMLInputElement).value);
    lastfm = await invoke<LastFmStatus>("set_scrobble_percent", { percent: value });
  }

  async function signIn() {
    busy = true;
    try {
      lastfm = await invoke<LastFmStatus>("lastfm_sign_in");
      statusMsg = "Approve YTMD Lite in your browser to finish signing in";
    } catch (e) {
      statusMsg = String(e);
    } finally {
      busy = false;
    }
  }

  async function logout() {
    lastfm = await invoke<LastFmStatus>("lastfm_logout");
  }

  async function refreshAudioDevices() {
    try {
      statusMsg = "Refreshing audio devices…";
      audioDevices = await playerCtl.listAudioOutputs();
      audioListError = audioDevices.length <= 1 ? "Only the default output was found — allow mic access if prompted so device names appear." : null;
      statusMsg = `Found ${audioDevices.length} audio output option(s)`;
      // Keep Rust config in sync with labels for next launch
      await invoke("set_audio_output", { deviceId: audioDeviceId, label: audioLabel });
    } catch (e) {
      audioListError = String(e);
      statusMsg = String(e);
    }
  }

  async function onAudioDeviceChange(ev: Event) {
    const select = ev.target as HTMLSelectElement;
    const id = select.value;
    const label = select.selectedOptions[0]?.text || "System default";
    try {
      statusMsg = `Switching output to ${label}…`;
      await playerCtl.applySinkId(id, label);
      await invoke("set_audio_output", { deviceId: id, label });
      audioDeviceId = id;
      audioLabel = label;
      statusMsg = id ? `Audio playing through: ${label}` : "Audio output: system default";
    } catch (e) {
      statusMsg = String(e);
      audioListError = String(e);
    }
  }

  async function resetAudio() {
    try {
      await playerCtl.applySinkId("", "System default");
      await invoke("set_audio_output", { deviceId: "", label: "System default" });
      audioDeviceId = "";
      audioLabel = "System default";
      statusMsg = "Audio output reset to system default";
    } catch (e) {
      statusMsg = String(e);
    }
  }

  function shelvesOf(data: BrowseResult | null) {
    if (!data) return [];
    const shelves = data.shelves?.length
      ? data.shelves
      : data.items?.length
        ? [{ type: "shelf" as const, id: "items", title: "Results", items: data.items, thumbnails: [] }]
        : [];
    return shelves.filter((s) => (s.items?.length ?? 0) > 0);
  }
</script>

<div class="shell">
  <header class="app-bar" onmousedown={startDrag}>
    <div class="app-bar__brand">
      <span class="brand-text">ytmd-lite</span>
      <span class="brand-sub">workspace</span>
    </div>
    <div class="app-bar__center">
      <span class="breadcrumb">{breadcrumb}</span>
    </div>
    <div class="app-bar__actions">
      {#if !engine.signedIn}
        <button
          class="btn-signin"
          title="Sign in to YouTube Music"
          onclick={() => openLogin()}
        >
          <span class="material-symbols-outlined">login</span>
          Sign in
        </button>
      {/if}
      <button class="icon-btn" title="Settings" onclick={() => setPanel(panel === "settings" ? "home" : "settings")}>
        <span class="material-symbols-outlined">settings</span>
      </button>
      <button class="win-btn" title="Minimize" onclick={minimize}>
        <span class="material-symbols-outlined">remove</span>
      </button>
      <button class="win-btn" title="Maximize" onclick={toggleMaximize}>
        <span class="material-symbols-outlined">{maximized ? "fullscreen_exit" : "check_box_outline_blank"}</span>
      </button>
      <button class="win-btn win-btn--close" title="Close" onclick={close}>
        <span class="material-symbols-outlined">close</span>
      </button>
    </div>
  </header>

  <aside class="rail">
    <button class="rail-btn" class:active={railActive === "home"} title="Home" onclick={() => setPanel("home")}>
      <span class="material-symbols-outlined">home</span>
      <span class="rail-label">Home</span>
    </button>
    <button class="rail-btn" class:active={railActive === "explore"} title="Explore" onclick={() => setPanel("explore")}>
      <span class="material-symbols-outlined">explore</span>
      <span class="rail-label">Explore</span>
    </button>
    <button class="rail-btn" class:active={railActive === "library"} title="Library" onclick={() => setPanel("library")}>
      <span class="material-symbols-outlined">library_music</span>
      <span class="rail-label">Library</span>
    </button>
    <button class="rail-btn" class:active={railActive === "queue"} title="Queue" onclick={() => setPanel("queue")}>
      <span class="material-symbols-outlined">queue_music</span>
      <span class="rail-label">Queue</span>
    </button>
    <div class="rail-spacer"></div>
    <button class="rail-btn" class:active={railActive === "lastfm"} title="Last.fm" onclick={() => setPanel("lastfm")}>
      <span class="material-symbols-outlined">graphic_eq</span>
      <span class="rail-label">Last.fm</span>
      {#if lastfm.enabled}
        <span class="dot" class:ok={lastfm.authenticated}></span>
      {/if}
    </button>
    <button class="rail-btn" class:active={railActive === "settings"} title="Settings" onclick={() => setPanel("settings")}>
      <span class="material-symbols-outlined">tune</span>
      <span class="rail-label">Config</span>
    </button>
  </aside>

  <main class="content">
    {#if !engine.signedIn && (panel === "home" || panel === "explore" || panel === "library" || isSearching)}
      <div class="auth-banner">
        <span class="material-symbols-outlined">login</span>
        <div>
          <strong>Sign in to YouTube Music</strong>
          <div class="muted">Opens a Google login window. Your session stays in a background keeper.</div>
        </div>
        <button class="btn primary" onclick={() => openLogin()}>Sign in</button>
      </div>
    {/if}

    <form
      class="search-bar"
      onsubmit={(e) => {
        e.preventDefault();
        void runSearch();
      }}
    >
      <span class="material-symbols-outlined">search</span>
      <input
        bind:value={searchQuery}
        placeholder="search — songs, albums, artists…"
        oninput={onSearchInput}
      />
      {#if searchQuery.trim()}
        <button class="icon-btn" type="button" title="Clear" onclick={clearSearch}>
          <span class="material-symbols-outlined">close</span>
        </button>
      {/if}
      <button class="btn primary" type="submit" disabled={searchBusy}>Search</button>
    </form>

    <div class="search-filters" role="tablist" aria-label="Search filter">
      {#each SEARCH_FILTERS as f (f.id)}
        <button
          type="button"
          class="filter-chip"
          class:active={searchFilter === f.id}
          role="tab"
          aria-selected={searchFilter === f.id}
          onclick={() => setSearchFilter(f.id)}
        >
          {f.label}
        </button>
      {/each}
    </div>

    {#if !isSearching && recentSearches.length && (panel === "home" || panel === "explore" || panel === "library")}
      <div class="chips-block">
        <div class="chips-label">Recent</div>
        <div class="chips">
          {#each recentSearches as term}
            <button type="button" class="chip" onclick={() => applySearchTerm(term)}>{term}</button>
          {/each}
        </div>
      </div>
    {/if}

    {#if isSearching && panel !== "detail" && searchSuggestionsList.length}
      <div class="chips-block">
        <div class="chips-label">Suggestions</div>
        <div class="chips">
          {#each searchSuggestionsList as term}
            <button type="button" class="chip" onclick={() => applySearchTerm(term)}>{term}</button>
          {/each}
        </div>
      </div>
    {/if}

    {#if panel === "detail"}
      <div class="browse">
        <button class="back" onclick={() => setPanel(detailFrom || "home")}>
          <span class="material-symbols-outlined">arrow_back</span>
          Back
        </button>
        <div class="detail-hero">
          <div class="detail-art">
            {#if detailCover}
              <img src={detailCover} alt="" referrerpolicy="no-referrer" />
            {:else}
              <span class="material-symbols-outlined">album</span>
            {/if}
          </div>
          <div class="detail-meta">
            <div class="detail-kind muted">{detailItem?.type || "collection"}</div>
            <h1 class="detail-title">{detailData?.title || detail?.title || "Collection"}</h1>
            {#if detailData?.artistLinks?.length || detailData?.subtitle}
              <div class="detail-sub">
                <ArtistLinks
                  artists={detailData?.artistLinks}
                  fallback={detailData?.subtitle || ""}
                  onopen={openArtist}
                />
              </div>
            {/if}
            {#if detailData?.kind === "artist"}
              {#if detailData.meta}
                <div class="detail-sub muted">{detailData.meta}</div>
              {/if}
            {:else}
              <div class="detail-sub muted">
                {detailTrackCount} track{detailTrackCount === 1 ? "" : "s"}
              </div>
            {/if}
            <div class="detail-actions">
              <button
                class="btn primary"
                disabled={!detailTrackCount || loading}
                onclick={() => {
                  if (detailItem) void playFromUi(detailItem);
                  else if (detailData?.items?.length) void playerCtl.playItems(detailData.items, 0);
                }}
              >
                <span class="material-symbols-outlined">play_arrow</span>
                Play
              </button>
              <button
                class="btn"
                disabled={!detailTrackCount || loading}
                onclick={() => shufflePlayItem(detailItem)}
              >
                <span class="material-symbols-outlined">shuffle</span>
                Shuffle
              </button>
              <button
                class="btn"
                disabled={!detailTrackCount || loading}
                onclick={() => detailItem && queueItem(detailItem)}
              >
                <span class="material-symbols-outlined">playlist_add</span>
                Add to queue
              </button>
              {#if detailData?.kind === "artist"}
                <button
                  class="btn"
                  class:subscribed={detailData.subscribed}
                  disabled={subscribeBusy || loading}
                  onclick={() => void toggleArtistSubscribe()}
                >
                  <span class="material-symbols-outlined">
                    {detailData.subscribed ? "notifications_active" : "person_add"}
                  </span>
                  {detailData.subscribed ? "Subscribed" : "Subscribe"}
                </button>
              {/if}
            </div>
          </div>
        </div>

        {#if loadError}
          <div class="callout error">{loadError}</div>
          <button class="btn" onclick={() => (engine.signedIn ? refreshPanel() : openLogin())}>
            {engine.signedIn ? "Retry" : "Sign in"}
          </button>
        {/if}

        <div class="browse-body" class:dimmed={loading}>
          {#if detailData?.items?.length}
            <div class="track-list">
              {#each detailData.items as item, i (item.id + i)}
                <div
                  class="track-row"
                  class:liked={item.videoId && likedIds.has(item.videoId)}
                  role="button"
                  tabindex="0"
                  onclick={() => playFromUi(item)}
                  onkeydown={(e) => e.key === "Enter" && playFromUi(item)}
                  oncontextmenu={(e) => {
                    e.preventDefault();
                    openContext(item, e);
                  }}
                >
                  <span class="num">{i + 1}</span>
                  <div class="t-art">
                    {#if thumb(item)}
                      <img src={thumb(item)} alt="" referrerpolicy="no-referrer" />
                    {:else}
                      <span class="material-symbols-outlined">music_note</span>
                    {/if}
                  </div>
                  <div class="t-meta">
                    <div class="t-title">{item.title}</div>
                    <div class="t-sub">
                      <ArtistLinks
                        artists={item.artistLinks}
                        fallback={item.subtitle || item.type}
                        onopen={openArtist}
                      />
                    </div>
                  </div>
                  <button
                    type="button"
                    class="icon-btn like-btn"
                    class:on={!!(item.videoId && likedIds.has(item.videoId))}
                    title={item.videoId && likedIds.has(item.videoId) ? "Unlike" : "Like"}
                    onclick={(e) => {
                      e.stopPropagation();
                      void toggleLikeFor(item.videoId, item);
                    }}
                  >
                    <span class="material-symbols-outlined">favorite</span>
                  </button>
                  <span class="material-symbols-outlined">play_arrow</span>
                </div>
              {/each}
            </div>
          {/if}
          {#each shelvesOf(detailData) as shelf (shelf.id + shelf.title)}
            {#if !(detailData?.items?.length && shelf.id === "items")}
              <ShelfRow {shelf} onopen={openItem} onplay={playFromUi} oncontext={openContext} onartist={openArtist} />
            {/if}
          {/each}
        </div>

        {#if loading}
          <div class="spinner-overlay" aria-busy="true" aria-label="Loading">
            <span class="spinner"></span>
          </div>
        {/if}
      </div>
    {:else if isSearching}
      <div class="browse">
        <div class="page-head">
          <h1 class="page-title">
            {searchFilter === "all" ? "Results" : searchFilter}
          </h1>
          <button class="icon-btn" title="Clear search" onclick={clearSearch}>
            <span class="material-symbols-outlined">close</span>
          </button>
        </div>

        {#if loadError}
          <div class="callout error">{loadError}</div>
          <button class="btn" onclick={() => (engine.signedIn ? void runSearch() : openLogin())}>
            {engine.signedIn ? "Retry" : "Sign in"}
          </button>
        {/if}

        <div class="browse-body" class:dimmed={searchBusy}>
          {#if !searchBusy && !shelvesOf(searchData).length}
            <p class="muted">No results for “{searchQuery.trim()}”.</p>
          {/if}
          {#each shelvesOf(searchData) as shelf (shelf.id + shelf.title)}
            <ShelfRow {shelf} onopen={openItem} onplay={playFromUi} oncontext={openContext} onartist={openArtist} />
          {/each}
        </div>

        {#if searchBusy}
          <div class="spinner-overlay" aria-busy="true" aria-label="Loading">
            <span class="spinner"></span>
          </div>
        {/if}
      </div>
    {:else if panel === "home" || panel === "explore" || panel === "library" || panel === "queue"}
      <div class="browse">
        {#if panel === "queue"}
          <div class="page-head">
            <h1 class="page-title">Queue</h1>
            <button
              class="btn"
              disabled={!player.queue?.length}
              onclick={() => {
                playerCtl.clearQueue();
                statusMsg = "Queue cleared";
              }}
            >
              Clear
            </button>
          </div>
        {:else if panel !== "home"}
          <div class="page-head">
            <h1 class="page-title">{panel}</h1>
            <button class="icon-btn" title="Refresh" onclick={() => refreshPanel(panel)} disabled={loading}>
              <span class="material-symbols-outlined">refresh</span>
            </button>
          </div>
        {/if}

        {#if loadError}
          <div class="callout error">{loadError}</div>
          <button class="btn" onclick={() => (engine.signedIn ? refreshPanel() : openLogin())}>
            {engine.signedIn ? "Retry" : "Sign in"}
          </button>
        {/if}

        <div class="browse-body" class:dimmed={loading}>
        {#if panel === "queue"}
          <div class="queue-list">
            {#if !(player.queue && player.queue.length)}
              <p class="muted">Queue is empty — play something from Home.</p>
            {:else}
              {#each player.queue as item, index (item.videoId + index)}
                <button
                  class="queue-row"
                  class:selected={item.selected || index === player.queueIndex}
                  onclick={() => playQueueIndex(index)}
                >
                  <div class="q-art">
                    {#if thumb(item)}
                      <img src={thumb(item)} alt="" referrerpolicy="no-referrer" />
                    {:else}
                      <span class="material-symbols-outlined">music_note</span>
                    {/if}
                  </div>
                  <div class="q-meta">
                    <div class="q-title">{item.title}</div>
                    <div class="q-sub">
                      <ArtistLinks
                        artists={item.channelId
                          ? [{ name: item.author, browseId: item.channelId }]
                          : []}
                        fallback={item.author}
                        onopen={openArtist}
                      />
                    </div>
                  </div>
                  <span class="q-idx">{index + 1}</span>
                </button>
              {/each}
            {/if}
          </div>
        {:else if panel === "home"}
          {#each shelvesOf(homeData) as shelf (shelf.id + shelf.title)}
            <ShelfRow {shelf} onopen={openItem} onplay={playFromUi} oncontext={openContext} onartist={openArtist} />
          {/each}
        {:else if panel === "explore"}
          {#each shelvesOf(exploreData) as shelf (shelf.id + shelf.title)}
            <ShelfRow {shelf} onopen={openItem} onplay={playFromUi} oncontext={openContext} onartist={openArtist} />
          {/each}
        {:else if panel === "library"}
          {#each shelvesOf(libraryData) as shelf (shelf.id + shelf.title)}
            <ShelfRow {shelf} onopen={openItem} onplay={playFromUi} oncontext={openContext} onartist={openArtist} />
          {/each}
        {/if}
        </div>

        {#if loading}
          <div class="spinner-overlay" aria-busy="true" aria-label="Loading">
            <span class="spinner"></span>
          </div>
        {/if}
      </div>
    {:else if panel === "lastfm"}
      <div class="panel">
        <div class="panel-head">
          <h1>Last.fm</h1>
          <p class="muted">Scrobble from the Material IDE shell — no API keys for users.</p>
        </div>
        {#if !lastfm.hasCredentials}
          <div class="callout error">Last.fm is not available in this build.</div>
        {:else if !lastfm.authenticated}
          <p class="hint">
            Approve YTMD Lite in your browser. Scrobbling turns on automatically after sign-in.
          </p>
          <button class="btn primary" disabled={busy || lastfm.signingIn} onclick={signIn}>
            {lastfm.signingIn ? "Waiting for approval…" : "Sign in with Last.fm"}
          </button>
        {:else}
          <div class="signed-in">
            <span class="material-symbols-outlined">account_circle</span>
            <div>
              <div class="signed-in-label">Signed in as</div>
              <div class="signed-in-user">{lastfm.username ?? "Last.fm user"}</div>
            </div>
          </div>
          <label class="row">
            <input
              type="checkbox"
              checked={lastfm.enabled}
              disabled={busy}
              onchange={(e) => setEnabled((e.currentTarget as HTMLInputElement).checked)}
            />
            <span>Enable scrobbling</span>
          </label>
          <label class="block">
            <span>Scrobble at {lastfm.scrobblePercent}%</span>
            <input type="range" min="50" max="95" step="5" value={lastfm.scrobblePercent} oninput={setPercent} />
          </label>
          <button class="btn" onclick={logout}>Sign out</button>
        {/if}
        {#if lastfm.lastError}
          <div class="callout error">{lastfm.lastError}</div>
        {/if}
        {#if lastfm.nowPlaying}
          <p class="muted">Now playing: {lastfm.nowPlaying}</p>
        {/if}
        {#if lastfm.lastScrobble}
          <p class="muted">Last scrobble: {lastfm.lastScrobble}</p>
        {/if}
      </div>
    {:else if panel === "settings"}
      <div class="panel">
        <div class="panel-head">
          <h1>Settings</h1>
          <p class="muted">Account, audio output, and paths.</p>
        </div>

        <section class="settings-block">
          <h2 class="settings-label">Google / YouTube Music</h2>
          {#if engine.signedIn}
            <div class="signed-in">
              <span class="material-symbols-outlined">account_circle</span>
              <div>
                <div class="signed-in-label">Signed in</div>
                <div class="signed-in-user">{authProfile ?? "Google account"}</div>
              </div>
            </div>
            <div class="row-actions">
              <button class="btn" disabled={busy} onclick={signOutGoogle}>Sign out</button>
              <button class="btn" disabled={busy} onclick={() => openLogin()}>Switch account</button>
            </div>
          {:else}
            <p class="hint muted">Not signed in. Library and personalized home need a Google session.</p>
            <div class="row-actions">
              <button class="btn primary" disabled={busy} onclick={() => openLogin()}>
                Sign in with Google
              </button>
            </div>
          {/if}
        </section>

        <section class="settings-block">
          <h2 class="settings-label">Appearance</h2>
          <p class="muted" style="margin: 0 0 10px">
            Accent color for buttons, highlights, and the playbar. Accepts a picker, hex, RGB, or CMYK.
          </p>
          <AccentPicker value={accentColor} onchange={onAccentChange} />
        </section>

        <section class="settings-block">
          <h2 class="settings-label">Discord</h2>
          <label class="row">
            <input
              type="checkbox"
              checked={discordRpc.enabled}
              disabled={busy}
              onchange={(e) => setDiscordRpc((e.currentTarget as HTMLInputElement).checked)}
            />
            <span>Enable Rich Presence</span>
          </label>
          <p class="muted">
            Show the current track on your Discord profile. Off by default.
            {#if discordRpc.enabled}
              {#if discordRpc.connected}
                {" "}· connected
              {:else}
                {" "}· waiting for Discord
              {/if}
            {/if}
          </p>
          {#if discordRpc.enabled && discordRpc.lastError}
            <div class="callout error">{discordRpc.lastError}</div>
          {/if}
        </section>

        <section class="settings-block">
          <h2 class="settings-label">Audio</h2>
          <label class="block">
            <span>Audio output</span>
            <select value={audioDeviceId} onchange={onAudioDeviceChange}>
              {#each audioDevices as d}
                <option value={d.id}>{d.label}</option>
              {/each}
            </select>
            <span class="muted">Uses the browser setSinkId API so playback follows this device.</span>
          </label>
          {#if audioListError}
            <div class="callout error">{audioListError}</div>
          {/if}
          <div class="row-actions">
            <button class="btn" onclick={refreshAudioDevices}>Refresh devices</button>
            <button class="btn" onclick={resetAudio}>Reset to default</button>
          </div>
          <p class="muted">Current: {audioLabel}</p>
        </section>

        <p class="muted path">Config: {configPath}</p>
      </div>
    {/if}
  </main>

  <PlayerDock
    {player}
    onqueue={() => setPanel("queue")}
    onlike={toggleLikeCurrent}
    onartist={openArtist}
  />

  <ContextMenu
    open={ctx.open}
    x={ctx.x}
    y={ctx.y}
    actions={contextActions(ctx.item)}
    onselect={onContextSelect}
    onclose={() => (ctx = { ...ctx, open: false })}
  />

  <footer class="status">
    <span>{statusMsg}</span>
    <span class="spacer"></span>
    <span class="muted">{engine.signedIn ? "signed in" : "signed out"} · API {engine.ready ? "ready" : "…"}</span>
  </footer>
</div>

<style>
  .shell {
    width: 100%;
    height: 100%;
    min-height: 100%;
    display: grid;
    grid-template-columns: var(--rail-w) minmax(0, 1fr);
    grid-template-rows: var(--app-bar-h) minmax(0, 1fr) var(--player-h) var(--status-h);
    grid-template-areas:
      "bar bar"
      "rail content"
      "player player"
      "status status";
    background: var(--md-sys-color-surface);
  }
  .app-bar {
    grid-area: bar;
    display: flex;
    align-items: center;
    height: var(--app-bar-h);
    padding: 0 6px 0 10px;
    background: var(--md-sys-color-surface-container-lowest);
    border-bottom: var(--hairline);
    -webkit-app-region: drag;
  }
  .app-bar__brand {
    display: flex;
    align-items: center;
    gap: 8px;
    min-width: 160px;
  }
  .brand-text {
    font-weight: 600;
    font-size: 12px;
    color: var(--md-sys-color-primary);
  }
  .brand-sub {
    font-size: 10px;
    padding: 1px 6px;
    border-radius: var(--radius);
    border: 1px solid var(--md-sys-color-outline);
    color: var(--md-sys-color-on-surface-variant);
    text-transform: lowercase;
  }
  .app-bar__center {
    flex: 1;
    text-align: center;
  }
  .breadcrumb {
    color: var(--md-sys-color-on-surface-variant);
    font-size: 11px;
  }
  .breadcrumb::before {
    content: "~/";
    opacity: 0.55;
  }
  .app-bar__actions {
    display: flex;
    gap: 2px;
    -webkit-app-region: no-drag;
  }
  .rail {
    grid-area: rail;
    display: flex;
    flex-direction: column;
    gap: 2px;
    padding: 8px 4px;
    background: var(--md-sys-color-surface-container-lowest);
    border-right: var(--hairline);
  }
  .rail-spacer {
    flex: 1;
  }
  .rail-btn {
    position: relative;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 2px;
    padding: 8px 0;
    border-radius: var(--radius);
    border-left: 2px solid transparent;
    color: var(--md-sys-color-on-surface-variant);
  }
  .rail-btn:hover {
    background: var(--md-sys-color-surface-container);
    color: var(--md-sys-color-on-surface);
  }
  .rail-btn:active {
    background: var(--md-sys-color-surface-container-high);
  }
  .rail-btn.active {
    color: var(--md-sys-color-primary);
    background: color-mix(in srgb, var(--md-sys-color-primary) 10%, transparent);
    border-left-color: var(--md-sys-color-primary);
  }
  .rail-btn.active .material-symbols-outlined {
    font-variation-settings: "FILL" 1, "wght" 500, "GRAD" 0, "opsz" 20;
  }
  .rail-label {
    font-size: 9px;
    text-transform: lowercase;
  }
  .dot {
    position: absolute;
    top: 6px;
    right: 8px;
    width: 6px;
    height: 6px;
    border-radius: var(--radius);
    background: var(--md-sys-color-error);
  }
  .dot.ok {
    background: #81c995;
  }
  .content {
    grid-area: content;
    overflow: auto;
    background: var(--md-sys-color-surface);
    position: relative;
    display: flex;
    flex-direction: column;
    min-height: 0;
  }
  .content.auth-open {
    overflow: hidden;
  }
  .auth-banner {
    height: 56px;
    box-sizing: border-box;
    flex-shrink: 0;
  }
  .content > .search-bar,
  .content > .search-filters,
  .content > .chips-block {
    flex-shrink: 0;
    margin-left: 20px;
    margin-right: 20px;
  }
  .content > .search-bar {
    margin-top: 14px;
    position: sticky;
    top: 0;
    z-index: 5;
    background: var(--md-sys-color-surface);
  }
  .content > .chips-block {
    margin-top: 0;
  }
  .search-filters {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin: 0 0 10px;
  }
  .filter-chip {
    height: 24px;
    padding: 0 10px;
    border-radius: var(--radius);
    border: 1px solid var(--md-sys-color-outline);
    background: var(--md-sys-color-surface-container-low);
    color: var(--md-sys-color-on-surface-variant);
    font: inherit;
    font-size: 11px;
    text-transform: lowercase;
    cursor: pointer;
  }
  .filter-chip:hover {
    border-color: var(--md-sys-color-primary);
    color: var(--md-sys-color-on-surface);
  }
  .filter-chip.active {
    background: color-mix(in srgb, var(--md-sys-color-primary) 18%, transparent);
    border-color: var(--md-sys-color-primary);
    color: var(--md-sys-color-primary);
  }
  .browse,
  .panel {
    padding: 16px 20px 28px;
    width: 100%;
    max-width: none;
    position: relative;
    flex: 1 1 auto;
    min-height: 0;
  }
  .browse-body.dimmed {
    opacity: 0.45;
    pointer-events: none;
    transition: opacity 0.15s ease;
  }
  .spinner-overlay {
    position: absolute;
    inset: 0;
    display: grid;
    place-items: center;
    pointer-events: none;
    z-index: 2;
  }
  .spinner {
    width: 22px;
    height: 22px;
    border-radius: var(--radius);
    border: 2px solid var(--md-sys-color-outline);
    border-top-color: var(--md-sys-color-primary);
    animation: spin 0.7s linear infinite;
  }
  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }
  .btn-signin {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    height: 26px;
    padding: 0 10px;
    margin-right: 4px;
    border-radius: var(--radius);
    font-size: 11px;
    font-weight: 500;
    background: var(--md-sys-color-primary);
    color: var(--md-sys-color-on-primary);
  }
  .btn-signin .material-symbols-outlined {
    font-size: 16px;
  }
  .page-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 12px;
    padding-bottom: 8px;
    border-bottom: var(--hairline);
  }
  .page-title {
    margin: 0 0 12px;
    font-size: 14px;
    font-weight: 600;
    text-transform: lowercase;
    color: var(--md-sys-color-primary);
  }
  .page-head .page-title {
    margin: 0;
  }
  .page-title::before {
    content: "# ";
    opacity: 0.55;
    font-weight: 400;
  }
  .search-bar {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 14px;
    padding: 6px 10px;
    border-radius: var(--radius);
    background: var(--md-sys-color-surface-container-lowest);
    border: 1px solid var(--md-sys-color-outline);
  }
  .search-bar:focus-within {
    border-color: var(--md-sys-color-primary);
  }
  .search-bar input {
    flex: 1;
    background: transparent;
    border: none;
    outline: none;
    color: inherit;
    font: inherit;
  }
  .search-bar input::placeholder {
    color: var(--md-sys-color-on-surface-variant);
  }
  .chips-block {
    margin: 0 0 14px;
  }
  .chips-label {
    font-size: 10px;
    color: var(--md-sys-color-on-surface-variant);
    margin-bottom: 6px;
    text-transform: lowercase;
  }
  .chips-label::before {
    content: "// ";
    opacity: 0.6;
  }
  .chips {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }
  .chip {
    padding: 4px 8px;
    border-radius: var(--radius);
    background: transparent;
    border: 1px solid var(--md-sys-color-outline);
    color: var(--md-sys-color-on-surface);
    font-size: 11px;
  }
  .chip:hover {
    border-color: var(--md-sys-color-primary);
    color: var(--md-sys-color-primary);
    background: color-mix(in srgb, var(--md-sys-color-primary) 8%, transparent);
  }
  .auth-banner {
    display: flex;
    align-items: center;
    gap: 12px;
    margin: 12px;
    padding: 10px 12px;
    border-radius: var(--radius);
    background: var(--md-sys-color-surface-container-high);
    border: 1px solid var(--md-sys-color-outline);
  }
  .back {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    margin-bottom: 8px;
    color: var(--md-sys-color-primary);
  }
  .detail-hero {
    display: flex;
    gap: 16px;
    align-items: flex-end;
    margin: 0 0 18px;
    padding-bottom: 14px;
    border-bottom: var(--hairline);
  }
  .detail-art {
    width: 140px;
    height: 140px;
    flex-shrink: 0;
    border-radius: var(--radius);
    overflow: hidden;
    background: var(--md-sys-color-surface-container-highest);
    display: grid;
    place-items: center;
    border: 1px solid var(--md-sys-color-outline-variant);
  }
  .detail-art img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
  .detail-art .material-symbols-outlined {
    font-size: 48px;
    opacity: 0.35;
  }
  .detail-meta {
    min-width: 0;
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 4px;
  }
  .detail-kind {
    font-size: 10px;
    text-transform: lowercase;
  }
  .detail-title {
    margin: 0;
    font-size: 18px;
    font-weight: 600;
    line-height: 1.25;
    color: var(--md-sys-color-primary);
  }
  .detail-sub {
    font-size: 11px;
    color: var(--md-sys-color-on-surface-variant);
  }
  .detail-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 10px;
  }
  .detail-actions .material-symbols-outlined {
    font-size: 16px;
  }
  .detail-actions .btn.subscribed {
    color: var(--md-sys-color-primary);
    border-color: color-mix(in srgb, var(--md-sys-color-primary) 45%, var(--md-sys-color-outline));
    background: color-mix(in srgb, var(--md-sys-color-primary) 12%, transparent);
  }
  .queue-list,
  .track-list {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }
  .queue-row,
  .track-row {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 6px 8px;
    border-radius: var(--radius);
    text-align: left;
    color: inherit;
    border-left: 2px solid transparent;
    cursor: pointer;
    transition: background 0.12s var(--ease-out), border-color 0.12s var(--ease-out);
  }
  .queue-row:hover,
  .track-row:hover {
    background: var(--md-sys-color-surface-container);
  }
  .queue-row:active,
  .track-row:active {
    background: var(--md-sys-color-surface-container-high);
  }
  .queue-row.selected {
    background: color-mix(in srgb, var(--md-sys-color-primary) 10%, transparent);
    border-left-color: var(--md-sys-color-primary);
  }
  .q-art {
    width: 36px;
    height: 36px;
    border-radius: var(--radius);
    overflow: hidden;
    background: var(--md-sys-color-surface-container-highest);
    display: grid;
    place-items: center;
  }
  .t-art {
    width: 36px;
    height: 36px;
    border-radius: var(--radius);
    overflow: hidden;
    background: var(--md-sys-color-surface-container-highest);
    display: grid;
    place-items: center;
    flex-shrink: 0;
  }
  .t-art img,
  .q-art img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
  .like-btn.on,
  .track-row.liked .like-btn {
    color: var(--md-sys-color-primary);
  }
  .like-btn.on .material-symbols-outlined,
  .track-row.liked .like-btn .material-symbols-outlined {
    font-variation-settings: "FILL" 1, "wght" 400, "GRAD" 0, "opsz" 24;
  }
  .q-meta,
  .t-meta {
    flex: 1;
    min-width: 0;
  }
  .q-title,
  .t-title {
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .q-sub,
  .t-sub {
    font-size: 11px;
    color: var(--md-sys-color-on-surface-variant);
  }
  .q-idx,
  .num {
    width: 28px;
    color: var(--md-sys-color-on-surface-variant);
    font-variant-numeric: tabular-nums;
  }
  .panel-head h1 {
    margin: 0 0 4px;
    font-size: 14px;
    font-weight: 600;
    text-transform: lowercase;
    color: var(--md-sys-color-primary);
  }
  .panel-head h1::before {
    content: "# ";
    opacity: 0.55;
    font-weight: 400;
  }
  .muted {
    color: var(--md-sys-color-on-surface-variant);
  }
  .hint {
    max-width: 480px;
    line-height: 1.5;
  }
  .callout {
    padding: 8px 10px;
    border-radius: var(--radius);
    margin: 12px 0;
    background: var(--md-sys-color-surface-container);
    border: 1px solid var(--md-sys-color-outline-variant);
  }
  .callout.error {
    color: var(--md-sys-color-error);
    border: 1px solid color-mix(in srgb, var(--md-sys-color-error) 35%, transparent);
  }
  .btn {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 6px 12px;
    border-radius: var(--radius);
    border: 1px solid var(--md-sys-color-outline);
    background: var(--md-sys-color-surface-container);
    color: var(--md-sys-color-on-surface);
    font-size: 11px;
  }
  .btn.primary {
    background: var(--md-sys-color-primary);
    border-color: var(--md-sys-color-primary);
    color: var(--md-sys-color-on-primary);
  }
  .btn:hover {
    border-color: var(--md-sys-color-primary);
    color: var(--md-sys-color-primary);
  }
  .btn.primary:hover {
    filter: brightness(1.06);
    color: var(--md-sys-color-on-primary);
  }
  .btn:disabled {
    opacity: 0.5;
  }
  .icon-btn,
  .win-btn {
    width: 28px;
    height: 28px;
    border-radius: var(--radius);
    display: grid;
    place-items: center;
    color: var(--md-sys-color-on-surface-variant);
  }
  .icon-btn:hover,
  .win-btn:hover {
    background: var(--md-sys-color-surface-container-high);
    color: var(--md-sys-color-on-surface);
  }
  .win-btn--close:hover {
    background: #c42b1c;
    color: white;
  }
  .signed-in {
    display: flex;
    align-items: center;
    gap: 12px;
    margin: 16px 0;
  }
  .signed-in-label {
    font-size: 11px;
    color: var(--md-sys-color-on-surface-variant);
  }
  .signed-in-user {
    font-weight: 500;
  }
  .row {
    display: flex;
    align-items: center;
    gap: 8px;
    margin: 12px 0;
  }
  .block {
    display: flex;
    flex-direction: column;
    gap: 6px;
    margin: 12px 0;
    max-width: 360px;
  }
  .block select,
  .block input[type="range"] {
    width: 100%;
  }
  select {
    background: var(--md-sys-color-surface-container-lowest);
    color: inherit;
    border: 1px solid var(--md-sys-color-outline);
    border-radius: var(--radius);
    padding: 6px 8px;
  }
  .row-actions {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin: 12px 0;
  }
  .path {
    font-size: 11px;
    word-break: break-all;
  }
  .settings-block {
    margin: 0 0 20px;
    padding-bottom: 16px;
    border-bottom: var(--hairline);
  }
  .settings-label {
    margin: 0 0 10px;
    font-size: 11px;
    font-weight: 600;
    text-transform: lowercase;
    color: var(--md-sys-color-on-surface-variant);
  }
  .settings-label::before {
    content: "// ";
    opacity: 0.55;
  }
  .status {
    grid-area: status;
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 0 10px;
    font-size: 10px;
    background: #0a0d12;
    color: var(--term-muted);
    border-top: 1px solid var(--term-border);
  }
  .status > span:first-child {
    color: var(--term-accent);
    animation: status-pulse 2.4s ease-in-out 1;
  }
  .spacer {
    flex: 1;
  }
</style>
