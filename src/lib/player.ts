import { invoke } from "@tauri-apps/api/core";
import { API_BASE, streamUrl } from "./api";
import type { MusicItem, PlayerState, QueueItem } from "./types";

type Listener = (state: PlayerState) => void;

let audio: HTMLAudioElement | null = null;
let queue: QueueItem[] = [];
let queueIndex = -1;
let volume = 100;
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

function ensureAudio() {
  if (audio) return audio;
  audio = new Audio();
  audio.preload = "auto";
  audio.addEventListener("ended", () => {
    userPaused = false;
    void next();
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
    if (!userPaused) sync("Buffering");
  });
  audio.addEventListener("playing", () => {
    if (userPaused) {
      audio?.pause();
      return;
    }
    sync("Playing");
  });
  audio.addEventListener("error", () => {
    const code = audio?.error?.code;
    const msg =
      code === MediaError.MEDIA_ERR_SRC_NOT_SUPPORTED
        ? "Stream format not supported (or API returned an error page)"
        : code === MediaError.MEDIA_ERR_NETWORK
          ? "Network error loading stream — is the catalog API running on :9847?"
          : "Audio playback error";
    userPaused = true;
    pushState(buildState("Paused"));
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
  if (userPaused) {
    resolved = "Paused";
  } else if (loading || trackState === "Buffering") {
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
          album: "",
          durationSeconds: duration || 0,
          thumbnails: item.thumbnails || [],
        }
      : null,
    trackState: resolved,
    videoProgress: a?.currentTime ?? 0,
    volume,
    muted: a?.muted ?? false,
    likeStatus,
    queue: queue.map((q, i) => ({ ...q, selected: i === queueIndex })),
    queueIndex,
    playlistId: "",
    shuffle: shuffleOn,
    repeat: repeatMode,
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
    author: item.subtitle || "",
    thumbnails: item.thumbnails || [],
    selected: false,
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
      return "Catalog API unreachable on :9847 — restart the app or run Kodama’s python backend";
    }
    return e.message || e.name;
  }
  return String(e);
}

/** Prefer proxied audio-stream; fall back to direct googlevideo URL from /stream. */
async function resolveSrc(videoId: string): Promise<string> {
  try {
    const warm = await fetch(`${API_BASE}/audio-stream/${encodeURIComponent(videoId)}/warm`);
    if (warm.ok) {
      const body = await warm.json().catch(() => ({}));
      if (!(body && body.ok === false)) {
        return streamUrl(videoId);
      }
    }
  } catch (e) {
    throw new Error(errMsg(e));
  }

  try {
    const res = await fetch(`${API_BASE}/stream/${encodeURIComponent(videoId)}`);
    const data = await res.json().catch(() => ({}));
    if (res.ok && typeof data.url === "string" && data.url) {
      return data.url;
    }
    if (data.premium_only) throw new Error("This track requires YouTube Premium");
    if (data.error) throw new Error(String(data.error));
  } catch (e) {
    if (e instanceof Error && !/failed to fetch/i.test(e.message)) throw e;
  }

  return streamUrl(videoId);
}

function waitCanPlay(a: HTMLAudioElement, timeoutMs = 45000): Promise<void> {
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
    const timer = setTimeout(() => {
      cleanup();
      reject(new Error("Timed out loading audio stream"));
    }, timeoutMs);
    const cleanup = () => {
      clearTimeout(timer);
      a.removeEventListener("canplay", onReady);
      a.removeEventListener("error", onErr);
    };
    a.addEventListener("canplay", onReady);
    a.addEventListener("error", onErr);
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
  return mapped.length;
}

export function clearQueue() {
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
  }
  sync("Unknown");
}

export function toggleShuffle(): boolean {
  shuffleOn = !shuffleOn;
  if (shuffleOn && queue.length) rebuildShuffleBag(queueIndex >= 0 ? queueIndex : undefined);
  else shuffleBag = [];
  sync();
  return shuffleOn;
}

export function toggleRepeat(): "off" | "one" | "all" {
  repeatMode = repeatMode === "off" ? "all" : repeatMode === "all" ? "one" : "off";
  sync();
  return repeatMode;
}

export function setLikeStatus(status: string) {
  likeStatus = status || "INDIFFERENT";
  sync();
}

export function getLikeStatus() {
  return likeStatus;
}

async function loadCurrent(autoplay: boolean) {
  const item = currentItem();
  if (!item) return;
  const a = ensureAudio();
  userPaused = false;
  loading = true;
  sync("Buffering");
  startProgress();

  try {
    const src = await resolveSrc(item.videoId);
    if (userPaused) {
      sync("Paused");
      return;
    }
    a.src = src;
    a.volume = volume / 100;
    await applySinkId(preferredSinkId);
    a.load();
    sync("Buffering");

    if (autoplay) {
      await waitCanPlay(a);
      if (userPaused) {
        sync("Paused");
        return;
      }
      loading = false;
      await a.play();
      sync("Playing");
    } else {
      loading = false;
      sync("Paused");
    }
  } catch (e) {
    loading = false;
    userPaused = true;
    sync("Paused");
    throw new Error(errMsg(e));
  } finally {
    loading = false;
  }
}

export async function playQueueIndex(index: number) {
  if (index < 0 || index >= queue.length) return;
  queueIndex = index;
  userPaused = false;
  await loadCurrent(true);
}

export async function playPause() {
  const a = ensureAudio();
  if (!currentItem()) return;

  if (a.paused || userPaused) {
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
  userPaused = false;
  await loadCurrent(true);
}

export async function seek(seconds: number) {
  const a = ensureAudio();
  a.currentTime = seconds;
  sync();
}

export async function setVolume(v: number) {
  volume = Math.max(0, Math.min(100, v));
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
  const outs = all.filter((d) => d.kind === "audiooutput");
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
      const a = ensureAudio();
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
