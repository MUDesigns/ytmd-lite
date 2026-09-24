import type { PlayerState, QueueItem } from "./types";

export type QueueRules = { noRepeatArtists: boolean; unplayedOnly: boolean; keepAlbums: boolean };
export const emptyRules: QueueRules = { noRepeatArtists: false, unplayedOnly: false, keepAlbums: false };
export type ListeningSession = {
  id: string; name: string; savedAt: number; queue: QueueItem[]; index: number;
  position: number; volume: number; shuffle: boolean; repeat: "off" | "one" | "all";
  stopAfterAlbum?: string;
};
const SESSION_KEY = "ytmd.sessions.v1";
const HISTORY_KEY = "ytmd.heard.v1";

export function albumKey(item?: QueueItem): string {
  return item?.albumId || (item?.album ? `${item.author.toLowerCase()}::${item.album.toLowerCase()}` : "");
}
function artistKey(item: QueueItem): string {
  return item.channelId || item.author.trim().toLowerCase();
}

/** Keep current/past entries intact; preserve source order inside each album. */
export function arrangeQueue(queue: QueueItem[], index: number, rules: QueueRules, heard: ReadonlySet<string>) {
  const past = queue.slice(0, index + 1);
  let upcoming = queue.slice(index + 1);
  if (rules.unplayedOnly) upcoming = upcoming.filter(t => !heard.has(t.videoId));
  if (rules.noRepeatArtists) {
    const seen = new Set(past.map(artistKey).filter(Boolean));
    upcoming = upcoming.filter(t => {
      const key = artistKey(t);
      if (!key) return true;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
  }
  if (rules.keepAlbums) {
    const groups = new Map<string, QueueItem[]>();
    upcoming.forEach((t, i) => {
      const key = albumKey(t) || `unknown:${i}`;
      groups.set(key, [...(groups.get(key) || []), t]);
    });
    const currentAlbum = albumKey(queue[index]);
    const continuation = groups.get(currentAlbum) || [];
    groups.delete(currentAlbum);
    upcoming = [...continuation, ...[...groups.values()].flat()];
  }
  return [...past, ...upcoming];
}

export function readHeard(): Set<string> {
  try {
    const raw = JSON.parse(localStorage.getItem(HISTORY_KEY) || "[]");
    return new Set(Array.isArray(raw) ? raw.filter((v): v is string => typeof v === "string").slice(-5000) : []);
  } catch { return new Set(); }
}
export function rememberHeard(videoId: string) {
  const heard = readHeard();
  heard.delete(videoId);
  heard.add(videoId);
  try { localStorage.setItem(HISTORY_KEY, JSON.stringify([...heard].slice(-5000))); } catch { /* Optional listening history. */ }
}

export function sessionFromState(name: string, state: PlayerState): ListeningSession {
  if (!name.trim()) throw new Error("Give this session a name.");
  if (!state.queue?.length || (state.queueIndex ?? -1) < 0) throw new Error("Add music to the queue first.");
  return {
    id: crypto.randomUUID(), name: name.trim().slice(0, 80), savedAt: Date.now(),
    queue: state.queue.map(t => ({ ...t, thumbnails: [...t.thumbnails], selected: false })),
    index: state.queueIndex!, position: Math.max(0, state.videoProgress || 0),
    volume: state.volume ?? 100, shuffle: !!state.shuffle, repeat: state.repeat || "off",
    stopAfterAlbum: state.stopAfterAlbum,
  };
}

export function readSessions(): ListeningSession[] {
  try {
    const data: unknown = JSON.parse(localStorage.getItem(SESSION_KEY) || "[]");
    if (!Array.isArray(data)) return [];
    return data.filter((s): s is ListeningSession =>
      s && typeof s.id === "string" && typeof s.name === "string" &&
      Number.isFinite(s.savedAt) && Array.isArray(s.queue) && s.queue.length > 0 &&
      s.queue.every((t: QueueItem) => t && typeof t.videoId === "string" && !!t.videoId &&
        typeof t.title === "string" && typeof t.author === "string" &&
        Array.isArray(t.thumbnails) && t.thumbnails.every((v: unknown) => typeof v === "string") &&
        (t.album === undefined || typeof t.album === "string") &&
        (t.albumId === undefined || typeof t.albumId === "string") &&
        (t.autoplay === undefined || typeof t.autoplay === "boolean") &&
        (t.channelId === undefined || typeof t.channelId === "string")) &&
      Number.isInteger(s.index) && s.index >= 0 && s.index < s.queue.length &&
      Number.isFinite(s.position) && s.position >= 0 && Number.isFinite(s.volume) && s.volume >= 0 && s.volume <= 100 &&
      typeof s.shuffle === "boolean" && ["off", "one", "all"].includes(s.repeat) &&
      (s.stopAfterAlbum === undefined || typeof s.stopAfterAlbum === "string")
    ).slice(0, 30);
  } catch { return []; }
}
export function writeSessions(sessions: ListeningSession[]) {
  if (sessions.length > 30) throw new Error("You can save up to 30 sessions. Delete one before adding another.");
  try { localStorage.setItem(SESSION_KEY, JSON.stringify(sessions)); }
  catch { throw new Error("Could not save sessions. Local storage may be full or unavailable."); }
}
