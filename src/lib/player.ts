import { invoke } from "@tauri-apps/api/core";
import { API_BASE, streamUrl } from "./api";
import type { MusicItem, PlayerState, QueueItem } from "./types";
import { albumKey, arrangeQueue, readHeard, rememberHeard, type QueueRules, type ListeningSession } from "./listening";

type Listener = (state: PlayerState) => void;

const VOLUME_KEY = "ytmd.volume.v1";

function readVolume(): number {
  try {
    const saved: unknown = JSON.parse(localStorage.getItem(VOLUME_KEY) ?? "100");
    if (typeof saved === "number" && Number.isFinite(saved) && saved >= 0 && saved <= 100) return saved;
  } catch { /* Storage may be unavailable or contain invalid data. */ }
  return 100;
}

let audio: HTMLAudioElement | null = null;
let queue: QueueItem[] = [];
let queueIndex = -1;
let volume = readVolume();
let listeners = new Set<Listener>();
let progressTimer: ReturnType<typeof setInterval> | null = null;
/** User explicitly paused — ignore spurious play/playing events from the stream. */
let userPaused = false;
/** True while resolving stream URL / waiting for canplay. */
let loading = false;
/** MediaDeviceInfo.deviceId for HTMLAudioElement.setSinkId ("" = system default). */
let preferredSinkId = "";
let sinkLabel = "System default";
let shuffleOn = false;
let repeatMode: "off" | "one" | "all" = "off";
/** When shuffle is on, next track picks from remaining unplayed indices. */
let shuffleBag: number[] = [];
let likeStatus = "INDIFFERENT";
let activeLoad: AbortController | null = null;
let prewarm: { videoId: string; controller: AbortController } | null = null;
let playbackTiming: { videoId: string; started: number; resolved?: number } | null = null;
let playbackError = "";
let resumePosition = 0;
let recoveryAttempts = 0;
let waitingForAudio = false;
let stopAfterAlbum = "";
let heardSeconds = 0;
let lastHeardPosition = 0;
let recordedHeard = false;

function ensureAudio() {
  if (audio) return audio;
  audio = new Audio();
  audio.volume = volume / 100;
  audio.preload = "auto";
  audio.addEventListener("ended", () => {
    const item = currentItem();
    if (item) rememberHeard(item.videoId);
    if (stopAfterAlbum && albumKey(queue[queueIndex + 1]) !== stopAfterAlbum) {
      stopAfterAlbum = "";
      userPaused = true;
      audio?.pause();
      stopProgress();
      sync("Paused");
      return;
    }
    userPaused = false;
    void next().catch(e => console.error("[player] advance failed", e));
  });
  audio.addEventListener("play", () => {
    if (userPaused) {
      audio?.pause();
      return;
    }
    sync("Playing");
  });
  audio.addEventListener("pause", () => {
    if (userPaused) {
      sync("Paused");
      return;
    }
    // Browser stall pause — refresh progress only
    sync();
  });
  audio.addEventListener("waiting", () => {
    waitingForAudio = true;
    if (!userPaused) sync("Buffering");
  });
  audio.addEventListener("timeupdate", () => {
    if (!loading && !playbackError && audio) {
      resumePosition = audio.currentTime;
      const delta = audio.currentTime - lastHeardPosition;
      if (!audio.paused && delta > 0 && delta < 2) heardSeconds += delta;
      lastHeardPosition = audio.currentTime;
      if (!recordedHeard && heardSeconds >= 30 && currentItem()) {
        rememberHeard(currentItem()!.videoId);
        recordedHeard = true;
      }
    }
  });
  audio.addEventListener("playing", () => {
    waitingForAudio = false;
    if (userPaused) {
      audio?.pause();
      return;
    }
    if (playbackTiming?.resolved !== undefined) {
      console.info("[playback timing]", {
        videoId: playbackTiming.videoId,
        resolveMs: Math.round(playbackTiming.resolved - playbackTiming.started),
        audioMs: Math.round(performance.now() - playbackTiming.resolved),
        totalMs: Math.round(performance.now() - playbackTiming.started),
      });
      playbackTiming = null;
    }
    sync("Playing");
    prepareNext();
  });
  audio.addEventListener("error", () => {
    if (!audio?.error) return; // Ignore an error queued for a source we already replaced.
    const code = audio?.error?.code;
    const msg =
      code === MediaError.MEDIA_ERR_SRC_NOT_SUPPORTED
        ? "Stream format not supported (or API returned an error page)"
        : code === MediaError.MEDIA_ERR_NETWORK
          ? "Audio connection interrupted"
          : "Audio playback error";
    if (!loading && audio.currentTime > 0) resumePosition = audio.currentTime;
    if (code === MediaError.MEDIA_ERR_NETWORK && !userPaused && !loading && recoveryAttempts < 1) {
      recoveryAttempts += 1;
      console.warn("[player] recovering interrupted stream at", resumePosition);
      void loadCurrent(true, resumePosition, true).catch((e) => console.error("[player] recovery failed", e));
      return;
    }
    playbackError = "Audio stream interrupted — press Play to retry from this position";
    waitingForAudio = false;
    userPaused = true;
    pushState(buildState("Error"));
    console.error("[player]", msg, audio?.error);
  });
  audio.addEventListener("loadedmetadata", () => {
    if (!userPaused) sync();
  });
  return audio;
}

function currentItem(): QueueItem | null {
  if (queueIndex < 0 || queueIndex >= queue.length) return null;
  return queue[queueIndex];
}

function buildState(trackState = "Unknown"): PlayerState {
  const item = currentItem();
  const a = audio;
  const duration = a && Number.isFinite(a.duration) ? Math.floor(a.duration) : 0;

  let resolved = trackState;
  if (playbackError) {
    resolved = "Error";
  } else if (userPaused) {
    resolved = "Paused";
  } else if (loading || waitingForAudio || trackState === "Buffering") {
    resolved = "Buffering";
  } else if (trackState === "Unknown") {
    resolved = a && !a.paused ? "Playing" : item ? "Paused" : "Unknown";
  }

  return {
    videoDetails: item
      ? {
          id: item.videoId,
          title: item.title,
          author: item.author || "",
          album: item.album || "",
          albumId: item.albumId,
          channelId: item.channelId,
          durationSeconds: duration || 0,
          thumbnails: item.thumbnails || [],
        }
      : null,
    trackState: resolved,
    videoProgress: loading || playbackError ? resumePosition : a?.currentTime ?? 0,
    playbackError: playbackError || undefined,
    volume,
    muted: a?.muted ?? false,
    likeStatus,
    queue: queue.map((q, i) => ({ ...q, selected: i === queueIndex })),
    queueIndex,
    playlistId: "",
    shuffle: shuffleOn,
    repeat: repeatMode,
    stopAfterAlbum: stopAfterAlbum || undefined,
  };
}

function pushState(state: PlayerState) {
  for (const l of listeners) l(state);
  // Last.fm / tray — do not let late Rust echoes drive the chrome UI
  void invoke("ingest_player_state", { payload: state }).catch((e) => {
    console.warn("[player] ingest_player_state failed", e);
  });
}

function sync(trackState?: string) {
  pushState(buildState(trackState));
}

function startProgress() {
  if (progressTimer) return;
  progressTimer = setInterval(() => {
    const state = buildState(
      userPaused ? "Paused" : loading ? "Buffering" : "Unknown",
    );
    for (const l of listeners) l(state);
  }, 500);
}

function stopProgress() {
  if (progressTimer) {
    clearInterval(progressTimer);
    progressTimer = null;
  }
}

function toQueueItem(item: MusicItem): QueueItem | null {
  if (!item.videoId) return null;
  return {
    videoId: item.videoId,
    title: item.title,
    author: item.subtitle || item.artistLinks?.map((a) => a.name).join(", ") || "",
    thumbnails: item.thumbnails || [],
    selected: false,
    channelId: item.artistBrowseId || item.artistLinks?.find((a) => a.browseId)?.browseId,
    album: item.album,
    albumId: item.albumId,
  };
}

function shuffleArray<T>(arr: T[]): T[] {
  const a = [...arr];
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}

function rebuildShuffleBag(excludeIndex?: number) {
  const idxs = queue.map((_, i) => i).filter((i) => i !== excludeIndex);
  shuffleBag = shuffleArray(idxs);
}

function playableItems(items: MusicItem[]): QueueItem[] {
  return items.map(toQueueItem).filter((x): x is QueueItem => !!x);
}

function errMsg(e: unknown): string {
  if (e instanceof Error) {
    if (/failed to fetch|networkerror|load failed/i.test(e.message)) {
      return "Catalog API unreachable on :9847 — restart the app (ytmd-backend should start automatically)";
    }
    return e.message || e.name;
  }
  return String(e);
}

/** Resolve once; retrying through another endpoint repeats the same extraction. */
async function resolveSrc(videoId: string, signal: AbortSignal, refresh = false): Promise<string> {
  const res = await fetch(`${API_BASE}/audio-stream/${encodeURIComponent(videoId)}/warm${refresh ? "?refresh=1" : ""}`, { signal });
  const data = await res.json();
  if (data.premium_only) throw new Error("This track requires YouTube Premium");
  if (!res.ok || !data.ok) throw new Error(data.error || "Could not resolve audio stream");
  return streamUrl(videoId);
}

function prepareNext() {
  if (loading || userPaused || !currentItem()) return;
  let index = queueIndex + 1;
  if (repeatMode === "one") index = queueIndex;
  else if (shuffleOn && queue.length > 1) {
    if (!shuffleBag.length) rebuildShuffleBag(queueIndex);
    index = shuffleBag[0];
  } else if (index >= queue.length && repeatMode === "all") index = 0;
  const videoId = queue[index]?.videoId;
  if (stopAfterAlbum && albumKey(queue[index]) !== stopAfterAlbum) {
    prewarm?.controller.abort();
    prewarm = null;
    return;
  }
  if (prewarm?.videoId === videoId) return;
  prewarm?.controller.abort();
  prewarm = null;
  if (!videoId || videoId === currentItem()?.videoId) return;
  const controller = new AbortController();
  prewarm = { videoId, controller };
  const timer = setTimeout(() => controller.abort(), 30000);
  void resolveSrc(videoId, controller.signal).catch(() => {
    // Preparation is optional; a foreground request can retry later.
    if (prewarm?.controller === controller) prewarm = null;
  }).finally(() => clearTimeout(timer));
}

function waitCanPlay(a: HTMLAudioElement, signal: AbortSignal, timeoutMs = 20000): Promise<void> {
  signal.throwIfAborted();
  if (a.readyState >= HTMLMediaElement.HAVE_FUTURE_DATA) return Promise.resolve();
  return new Promise((resolve, reject) => {
    const onReady = () => {
      cleanup();
      resolve();
    };
    const onErr = () => {
      cleanup();
      reject(new Error("Failed to load audio stream"));
    };
    const onAbort = () => {
      cleanup();
      reject(signal.reason);
    };
    const timer = setTimeout(() => {
      cleanup();
      reject(new Error("Timed out loading audio stream"));
    }, timeoutMs);
    const cleanup = () => {
      clearTimeout(timer);
      a.removeEventListener("canplay", onReady);
      a.removeEventListener("error", onErr);
      signal.removeEventListener("abort", onAbort);
    };
    a.addEventListener("canplay", onReady);
    a.addEventListener("error", onErr);
    signal.addEventListener("abort", onAbort, { once: true });
  });
}

export function subscribe(listener: Listener) {
  listeners.add(listener);
  listener(buildState());
  return () => listeners.delete(listener);
}

export function getSnapshot(): PlayerState {
  return buildState();
}

export async function playItems(items: MusicItem[], startIndex = 0) {
  const mapped = playableItems(items);
  if (!mapped.length) throw new Error("No playable tracks");
  queue = mapped;
  stopAfterAlbum = "";
  queueIndex = Math.max(0, Math.min(startIndex, mapped.length - 1));
  likeStatus = "INDIFFERENT";
  if (shuffleOn) rebuildShuffleBag(queueIndex);
  else shuffleBag = [];
  userPaused = false;
  await loadCurrent(true);
}

/** Replace queue with shuffled order and start from the first. */
export async function playItemsShuffled(items: MusicItem[]) {
  const mapped = playableItems(items);
  if (!mapped.length) throw new Error("No playable tracks");
  queue = shuffleArray(mapped);
  stopAfterAlbum = "";
  queueIndex = 0;
  shuffleOn = true;
  likeStatus = "INDIFFERENT";
  rebuildShuffleBag(0);
  userPaused = false;
  await loadCurrent(true);
}

export async function playItem(item: MusicItem) {
  if (item.videoId) {
    await playItems([item], 0);
    return;
  }
  throw new Error("Item has no videoId — open the collection first");
}

/** Append tracks to the end of the queue (does not start playback). */
export function addToQueue(items: MusicItem | MusicItem[]) {
  const list = Array.isArray(items) ? items : [items];
  const mapped = playableItems(list);
  if (!mapped.length) return 0;
  const wasEmpty = queue.length === 0;
  queue = [...queue, ...mapped];
  if (shuffleOn) rebuildShuffleBag(queueIndex >= 0 ? queueIndex : undefined);
  if (wasEmpty) {
    queueIndex = 0;
    sync("Paused");
  } else {
    sync();
  }
  prepareNext();
  return mapped.length;
}

/** Insert tracks to play after the current track. */
export function playNext(items: MusicItem | MusicItem[]) {
  const list = Array.isArray(items) ? items : [items];
  const mapped = playableItems(list);
  if (!mapped.length) return 0;
  if (queue.length === 0 || queueIndex < 0) {
    return addToQueue(list);
  }
  const insertAt = queueIndex + 1;
  queue = [...queue.slice(0, insertAt), ...mapped, ...queue.slice(insertAt)];
  if (shuffleOn) rebuildShuffleBag(queueIndex);
  sync();
  prepareNext();
  return mapped.length;
}

export function clearQueue() {
  stopAfterAlbum = "";
  activeLoad?.abort();
  activeLoad = null;
  prewarm?.controller.abort();
  prewarm = null;
  playbackTiming = null;
  playbackError = "";
  resumePosition = 0;
  recoveryAttempts = 0;
  waitingForAudio = false;
  loading = false;
  userPaused = true;
  stopProgress();
  queue = [];
  queueIndex = -1;
  shuffleBag = [];
  likeStatus = "INDIFFERENT";
  const a = audio;
  if (a) {
    a.pause();
    a.removeAttribute("src");
    a.load();
  }
  sync("Unknown");
}

export function toggleShuffle(): boolean {
  stopAfterAlbum = "";
  shuffleOn = !shuffleOn;
  if (shuffleOn && queue.length) rebuildShuffleBag(queueIndex >= 0 ? queueIndex : undefined);
  else shuffleBag = [];
  sync();
  prepareNext();
  return shuffleOn;
}

export function toggleRepeat(): "off" | "one" | "all" {
  stopAfterAlbum = "";
  repeatMode = repeatMode === "off" ? "all" : repeatMode === "all" ? "one" : "off";
  sync();
  prepareNext();
  return repeatMode;
}

export function setLikeStatus(status: string) {
  likeStatus = status || "INDIFFERENT";
  sync();
}

export function getLikeStatus() {
  return likeStatus;
}

async function loadCurrent(autoplay: boolean, resumeAt = 0, recovering = false) {
  const item = currentItem();
  if (!item) return;
  const a = ensureAudio();
  const refresh = recovering || !!playbackError;
  if (!recovering) recoveryAttempts = 0;
  if (!recovering) { heardSeconds = 0; recordedHeard = false; }
  lastHeardPosition = resumeAt;
  resumePosition = resumeAt;
  playbackError = "";
  waitingForAudio = false;
  activeLoad?.abort();
  const controller = new AbortController();
  activeLoad = controller;
  if (prewarm?.videoId !== item.videoId) prewarm?.controller.abort();
  prewarm = null;
  const { signal } = controller;
  const started = performance.now();
  playbackTiming = { videoId: item.videoId, started };
  // Stop the previous stream and any pending play promise immediately.
  a.pause();
  a.removeAttribute("src");
  a.load();
  userPaused = false;
  loading = true;
  sync("Buffering");
  startProgress();

  const resolveTimer = setTimeout(() => controller.abort(new Error("Timed out resolving audio stream")), 30000);
  try {
    const src = await resolveSrc(item.videoId, signal, refresh);
    clearTimeout(resolveTimer);
    signal.throwIfAborted();
    playbackTiming = { videoId: item.videoId, started, resolved: performance.now() };
    a.src = src;
    a.volume = volume / 100;
    await applySinkId(preferredSinkId);
    signal.throwIfAborted();
    a.load();
    sync("Buffering");

    if (autoplay) {
      await waitCanPlay(a, signal);
      signal.throwIfAborted();
      if (resumePosition > 0) {
        a.currentTime = resumePosition;
        await waitCanPlay(a, signal);
        signal.throwIfAborted();
      }
      if (userPaused) {
        sync("Paused");
        return;
      }
      loading = false;
      await a.play();
      signal.throwIfAborted();
      sync("Playing");
      prepareNext();
    } else {
      loading = false;
      sync("Paused");
    }
  } catch (e) {
    if (activeLoad !== controller) return;
    // A newer selection aborts the old request; only this load owns its state.
    loading = false;
    userPaused = true;
    waitingForAudio = false;
    playbackError = "Audio stream unavailable — press Play to retry from this position";
    playbackTiming = null;
    a.pause();
    sync("Error");
    throw new Error(errMsg(e));
  } finally {
    clearTimeout(resolveTimer);
    if (activeLoad === controller) {
      loading = false;
      activeLoad = null;
    }
  }
}

export async function playQueueIndex(index: number) {
  if (index < 0 || index >= queue.length) return;
  queueIndex = index;
  if (stopAfterAlbum && albumKey(queue[index]) !== stopAfterAlbum) stopAfterAlbum = "";
  userPaused = false;
  await loadCurrent(true);
}

export async function playPause() {
  const a = ensureAudio();
  if (!currentItem()) return;
  if (loading) {
    userPaused = !userPaused;
    if (userPaused) a.pause();
    sync(userPaused ? "Paused" : "Buffering");
    return;
  }

  if (a.paused || userPaused) {
    if (playbackError || !a.getAttribute("src") || a.error) return loadCurrent(true, resumePosition);
    userPaused = false;
    try {
      await a.play();
      sync("Playing");
      startProgress();
    } catch (e) {
      userPaused = true;
      sync("Paused");
      throw e;
    }
  } else {
    userPaused = true;
    a.pause();
    sync("Paused");
  }
}

export async function next() {
  if (stopAfterAlbum && albumKey(queue[queueIndex + 1]) !== stopAfterAlbum) stopAfterAlbum = "";
  if (repeatMode === "one" && queueIndex >= 0) {
    const a = ensureAudio();
    a.currentTime = 0;
    userPaused = false;
    try {
      await a.play();
      sync("Playing");
    } catch {
      await loadCurrent(true);
    }
    return;
  }

  let nextIndex = -1;
  if (shuffleOn && queue.length > 1) {
    if (!shuffleBag.length) rebuildShuffleBag(queueIndex);
    nextIndex = shuffleBag.shift() ?? -1;
    if (nextIndex < 0 && repeatMode === "all") {
      rebuildShuffleBag(queueIndex);
      nextIndex = shuffleBag.shift() ?? -1;
    }
  } else if (queueIndex + 1 < queue.length) {
    nextIndex = queueIndex + 1;
  } else if (repeatMode === "all" && queue.length) {
    nextIndex = 0;
  }

  if (nextIndex < 0) {
    userPaused = true;
    stopProgress();
    sync("Paused");
    return;
  }
  queueIndex = nextIndex;
  userPaused = false;
  await loadCurrent(true);
}

export async function previous() {
  const a = ensureAudio();
  if (a.currentTime > 3) {
    a.currentTime = 0;
    sync();
    return;
  }
  if (queueIndex <= 0) {
    a.currentTime = 0;
    sync();
    return;
  }
  queueIndex -= 1;
  if (stopAfterAlbum && albumKey(currentItem() || undefined) !== stopAfterAlbum) stopAfterAlbum = "";
  userPaused = false;
  await loadCurrent(true);
}

export async function seek(seconds: number) {
  const a = ensureAudio();
  resumePosition = Math.max(0, seconds);
  lastHeardPosition = resumePosition;
  if (playbackError || loading) {
    sync();
    return;
  }
  a.currentTime = seconds;
  sync();
}

/** Rules are applied explicitly to the upcoming queue after the user previews them. */
export function applyQueueRules(rules: QueueRules) {
  queue = arrangeQueue(queue, queueIndex, rules, readHeard());
  shuffleOn = false;
  shuffleBag = [];
  sync();
  prepareNext();
}

export function setStopAfterAlbum(enabled: boolean) {
  const key = albumKey(currentItem() || undefined);
  if (enabled && !key) throw new Error("Album information is unavailable for this track. Start playback from an album page.");
  stopAfterAlbum = enabled ? key : "";
  if (enabled) { shuffleOn = false; shuffleBag = []; repeatMode = "off"; }
  sync();
  prepareNext();
}

export async function restoreSession(session: ListeningSession) {
  if (!session.queue.length || !session.queue[session.index]) throw new Error("This session has no playable queue.");
  clearQueue();
  queue = session.queue.map(t => ({ ...t, thumbnails: [...t.thumbnails], selected: false }));
  queueIndex = session.index;
  await setVolume(session.volume);
  shuffleOn = session.shuffle;
  repeatMode = session.repeat;
  stopAfterAlbum = session.stopAfterAlbum || "";
  if (stopAfterAlbum) { shuffleOn = false; repeatMode = "off"; }
  if (shuffleOn) rebuildShuffleBag(queueIndex);
  await loadCurrent(true, session.position);
}

export async function setVolume(v: number) {
  if (!Number.isFinite(v)) return;
  volume = Math.max(0, Math.min(100, v));
  try { localStorage.setItem(VOLUME_KEY, JSON.stringify(volume)); }
  catch { /* Keep volume controls working when storage is unavailable. */ }
  const a = ensureAudio();
  a.volume = volume / 100;
  sync();
}

export async function listAudioOutputs(): Promise<Array<{ id: string; label: string }>> {
  // Labels are blank until we unlock device access once.
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true, video: false });
    stream.getTracks().forEach((t) => t.stop());
  } catch {
    /* still try enumerate — may only get default */
  }
  const all = await navigator.mediaDevices.enumerateDevices();
  const outs = [...new Map(all.filter((d) => d.kind === "audiooutput" && d.deviceId).map(d => [d.deviceId, d])).values()];
  const devices = [
    { id: "", label: "System default" },
    ...outs.map((d, i) => ({
      id: d.deviceId,
      label: d.label || `Output ${i + 1}`,
    })),
  ];
  return devices;
}

export function getPreferredSink(): { id: string; label: string } {
  return { id: preferredSinkId, label: sinkLabel };
}

export async function applySinkId(deviceId: string, label?: string) {
  preferredSinkId = deviceId || "";
  if (label !== undefined) sinkLabel = label || (preferredSinkId ? sinkLabel : "System default");
  const a = ensureAudio();
  const anyA = a as HTMLAudioElement & { setSinkId?: (id: string) => Promise<void> };
  if (typeof anyA.setSinkId !== "function") {
    throw new Error("This WebView cannot route audio to a specific device (setSinkId missing)");
  }
  try {
    await anyA.setSinkId(preferredSinkId);
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    throw new Error(`Could not switch audio output: ${msg}`);
  }
}

export async function handleMediaCommand(command: string) {
  if (command === "playPause" || command === "play" || command === "pause") {
    if (command === "play") {
      userPaused = false;
      if (loading) {
        sync("Buffering");
        return;
      }
      const a = ensureAudio();
      if (playbackError || !a.getAttribute("src") || a.error) return loadCurrent(true, resumePosition);
      await a.play();
      sync("Playing");
      return;
    }
    if (command === "pause") {
      userPaused = true;
      ensureAudio().pause();
      sync("Paused");
      return;
    }
    await playPause();
    return;
  }
  if (command === "next") return next();
  if (command === "previous") return previous();
  if (command.startsWith("seek:")) {
    const sec = Number(command.slice(5));
    if (!Number.isNaN(sec)) await seek(sec);
    return;
  }
  if (command.startsWith("volume:")) {
    const v = Number(command.slice(7));
    if (!Number.isNaN(v)) await setVolume(v);
  }
}
