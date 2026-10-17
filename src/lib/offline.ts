import { get, writable } from "svelte/store";
import { API_BASE, mapItem } from "./api";
import type {
  DownloadEntry, DownloadStatus, MusicItem, OfflineCollection, OfflineCollectionKind, OfflineLibrary,
  OfflineSong, QueueItem,
} from "./types";

export type OfflineState = {
  library: OfflineLibrary | null;
  downloads: Record<string, DownloadEntry>;
  /** Songs on disk. */
  downloadedIds: Set<string>;
  /** Saved collections (`kind:id`). */
  collectionKeys: Set<string>;
  active: number;
  queued: number;
  failed: number;
  generation: number;
  error: string | null;
};

const initial: OfflineState = {
  library: null, downloads: {}, downloadedIds: new Set(), collectionKeys: new Set(),
  active: 0, queued: 0, failed: 0, generation: -1, error: null,
};

export const offline = writable<OfflineState>(initial);

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`${API_BASE}${path}`, init);
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    if (/failed to fetch|networkerror|load failed/i.test(msg)) {
      throw new Error("Catalog API unreachable on :9847 — restart the app (it should start ytmd-backend automatically)");
    }
    throw e instanceof Error ? e : new Error(msg);
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok || (data && typeof data === "object" && "error" in data && data.error)) {
    throw new Error((data as { error?: string }).error || `HTTP ${res.status}`);
  }
  return data as T;
}

function post(path: string, body?: unknown, method = "POST") {
  return request<Record<string, unknown>>(path, {
    method,
    headers: body !== undefined ? { "Content-Type": "application/json" } : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
}

/** Backend errors that mean YouTube is unreachable rather than the request being wrong. */
export function isOfflineError(message: string): boolean {
  return /getaddrinfo|name resolution|ENOTFOUND|ECONNRESET|ECONNREFUSED|Max retries|NewConnectionError|Temporary failure|Network is unreachable|ConnectionError|Failed to establish|unreachable|timed out|Timeout/i
    .test(message) && !/:9847/.test(message);
}

export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return "0 MB";
  const mb = bytes / (1024 * 1024);
  return mb >= 1024 ? `${(mb / 1024).toFixed(2)} GB` : `${mb.toFixed(mb >= 100 ? 0 : 1)} MB`;
}

/** The offline library key a collection item would be saved under, or null for songs and moods. */
export function collectionKeyFor(item: MusicItem | null | undefined): string | null {
  if (!item || item.offlineKey) return item?.offlineKey ?? null;
  if (item.type === "playlist" || (item.playlistId && item.type !== "song")) {
    const id = item.playlistId || item.browseId?.replace(/^VL/, "");
    return id ? `playlist:${id}` : null;
  }
  if (item.type === "album" && item.browseId) return `album:${item.browseId}`;
  if (item.type === "artist" && item.browseId) return `artist:${item.browseId}`;
  return null;
}

export function isDownloaded(state: OfflineState, item: MusicItem | null | undefined): boolean {
  if (!item) return false;
  if (item.videoId && item.type === "song") return state.downloadedIds.has(item.videoId);
  const key = collectionKeyFor(item);
  return !!key && state.collectionKeys.has(key);
}

/** Download progress of a collection: null when it is not saved, else done/total. */
export function collectionProgress(state: OfflineState, item: MusicItem | null | undefined) {
  const key = collectionKeyFor(item);
  const collection = key ? state.library?.collections.find((c) => c.key === key) : undefined;
  if (!collection) return null;
  const pending = collection.videoIds.filter((id) => {
    const status = state.downloads[id]?.status;
    return status === "queued" || status === "downloading";
  }).length;
  return { done: collection.done, total: collection.total, failed: collection.failed, pending };
}

function trackMeta(item: MusicItem | QueueItem): OfflineSong | null {
  if (!item.videoId) return null;
  if ("author" in item) {
    return {
      videoId: item.videoId, title: item.title, artists: item.author, artistBrowseId: item.channelId,
      album: item.album, albumBrowseId: item.albumId, thumbnail: item.thumbnails[0],
    };
  }
  return {
    videoId: item.videoId, title: item.title,
    artists: item.subtitle || item.artistLinks?.map((a) => a.name).join(", "),
    artistBrowseId: item.artistBrowseId, artistLinks: item.artistLinks,
    album: item.album, albumBrowseId: item.albumId, thumbnail: item.thumbnails[0],
  };
}

function applyStatus(state: OfflineState, status: DownloadStatus): OfflineState {
  return {
    ...state, downloads: status.downloads, active: status.active, queued: status.queued,
    failed: status.failed, generation: status.generation, error: null,
  };
}

function applyLibrary(state: OfflineState, library: OfflineLibrary): OfflineState {
  return applyStatus({
    ...state, library,
    downloadedIds: new Set(library.songs.map((s) => s.videoId)),
    collectionKeys: new Set(library.collections.map((c) => c.key)),
  }, library);
}

export async function refreshLibrary(): Promise<OfflineLibrary> {
  try {
    const library = await request<OfflineLibrary>("/offline/library");
    offline.update((s) => applyLibrary(s, library));
    schedulePoll();
    return library;
  } catch (e) {
    offline.update((s) => ({ ...s, error: String(e instanceof Error ? e.message : e) }));
    throw e;
  }
}

let pollTimer: ReturnType<typeof setTimeout> | null = null;
let polling = false;

async function pollOnce() {
  pollTimer = null;
  const before = get(offline).generation;
  try {
    const status = await request<DownloadStatus>("/offline/status");
    offline.update((s) => applyStatus(s, status));
    if (status.generation !== before) await refreshLibrary();
  } catch (e) {
    offline.update((s) => ({ ...s, error: String(e instanceof Error ? e.message : e) }));
  }
  schedulePoll();
}

function schedulePoll() {
  if (!polling || pollTimer) return;
  const { active, queued } = get(offline);
  pollTimer = setTimeout(() => void pollOnce(), active + queued > 0 ? 1500 : 15000);
}

/** Keep download progress fresh while the page is open. Returns a stop function. */
export function startPolling(): () => void {
  polling = true;
  schedulePoll();
  return () => {
    polling = false;
    if (pollTimer) clearTimeout(pollTimer);
    pollTimer = null;
  };
}

function pollSoon() {
  if (pollTimer) clearTimeout(pollTimer);
  pollTimer = null;
  if (polling) pollTimer = setTimeout(() => void pollOnce(), 300);
}

export async function downloadSong(item: MusicItem | QueueItem): Promise<string> {
  const meta = trackMeta(item);
  if (!meta) throw new Error("This item has no playable track to download.");
  const result = await post(`/offline/songs/${encodeURIComponent(meta.videoId)}`, meta);
  pollSoon();
  return String(result.status || "queued");
}

export async function removeSong(videoId: string) {
  await post(`/offline/songs/${encodeURIComponent(videoId)}`, undefined, "DELETE");
  await refreshLibrary();
}

export async function downloadCollection(
  kind: OfflineCollectionKind, id: string, tracks: Array<MusicItem | QueueItem>,
  info: { title?: string; subtitle?: string; thumbnail?: string },
): Promise<{ key: string; queued: number; total: number }> {
  const metas = tracks.map(trackMeta).filter((t): t is OfflineSong => !!t);
  if (!metas.length) throw new Error("Nothing to download: this collection has no playable tracks.");
  const result = await post("/offline/collections", { kind, id, tracks: metas, ...info });
  await refreshLibrary();
  pollSoon();
  return { key: String(result.key), queued: Number(result.queued) || 0, total: Number(result.total) || metas.length };
}

export async function removeCollection(key: string) {
  await post(`/offline/collections/${encodeURIComponent(key)}`, undefined, "DELETE");
  await refreshLibrary();
}

export async function cancelDownload(videoId: string) {
  await post(`/offline/cancel/${encodeURIComponent(videoId)}`);
  pollSoon();
}

export async function retryFailed(): Promise<number> {
  const result = await post("/offline/retry");
  pollSoon();
  return Number(result.queued) || 0;
}

export async function clearAllDownloads() {
  await post("/offline/clear");
  await refreshLibrary();
}

export function offlineSongItem(song: OfflineSong): MusicItem {
  return mapItem({
    type: "song", videoId: song.videoId, title: song.title || song.videoId, artists: song.artists,
    artistBrowseId: song.artistBrowseId, artistLinks: song.artistLinks, album: song.album,
    albumBrowseId: song.albumBrowseId, thumbnail: song.thumbnail,
  });
}

/** A browsable item for a saved collection; `items` holds only tracks already on disk. */
export function offlineCollectionItem(collection: OfflineCollection, downloadedIds: ReadonlySet<string>): MusicItem {
  const items = collection.tracks.filter((t) => downloadedIds.has(t.videoId)).map(offlineSongItem);
  const cover = collection.thumbnail || items.find((t) => t.thumbnails[0])?.thumbnails[0];
  const kindLabel = collection.kind === "artist" ? "Top songs" : collection.kind === "queue" ? "Saved queue" : collection.kind;
  return {
    type: collection.kind === "album" ? "album" : "playlist",
    id: collection.key,
    title: collection.title || kindLabel,
    subtitle: collection.subtitle || `${items.length} of ${collection.total} downloaded`,
    thumbnails: cover ? [cover] : [],
    browseId: collection.kind === "album" ? collection.id : undefined,
    playlistId: collection.kind === "playlist" ? collection.id : undefined,
    items,
    offlineKey: collection.key,
  };
}

export function offlineShelves(state: OfflineState): MusicItem[] {
  const library = state.library;
  if (!library) return [];
  const shelves: MusicItem[] = [];
  if (library.collections.length) {
    shelves.push({
      type: "shelf", id: "offline-collections", title: "Downloaded collections", thumbnails: [],
      items: library.collections.map((c) => offlineCollectionItem(c, state.downloadedIds)),
    });
  }
  if (library.songs.length) {
    shelves.push({
      type: "shelf", id: "offline-songs", title: "Downloaded songs", thumbnails: [],
      items: library.songs.map(offlineSongItem),
    });
  }
  return shelves;
}
