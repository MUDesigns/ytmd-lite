export const shareDestinations = [
  { id: "player", label: "YTMD Lite" },
  { id: "music", label: "YouTube Music" },
  { id: "youtube", label: "YouTube" },
] as const;

export type ShareDestination = typeof shareDestinations[number]["id"];
const videoIdPattern = /^[A-Za-z0-9_-]{11}$/;
const playlistIdPattern = /^[A-Za-z0-9_-]{2,200}$/;

function normalizedPlaylistId(value: string): string | null {
  const id = value.replace(/^VL/, "");
  return playlistIdPattern.test(id) && id !== "LM" ? id : null;
}

export function shareablePlaylistId(item: {
  type: string; id: string; playlistId?: string; browseId?: string;
} | null): string | null {
  if (!item || item.type !== "playlist") return null;
  return normalizedPlaylistId(item.playlistId || item.browseId || item.id);
}

export function playlistShareUrl(playlistId: string, destination: ShareDestination): string {
  const id = normalizedPlaylistId(playlistId);
  if (!id) throw new Error("This playlist has no shareable YouTube playlist ID.");
  switch (destination) {
    case "player": return `https://mudesigns.github.io/ytmd-lite/?playlist=${id}`;
    case "music": return `https://music.youtube.com/playlist?list=${id}`;
    case "youtube": return `https://www.youtube.com/playlist?list=${id}`;
  }
}

export function sharedPlaylistId(value: string): string | null {
  // Check the original path before URL parsing can normalize dot segments.
  if (!/^ytmd-lite:\/\/playlist\/[A-Za-z0-9_-]{2,202}$/.test(value)) return null;
  try {
    const url = new URL(value);
    if (url.protocol !== "ytmd-lite:" || url.hostname !== "playlist" || url.username || url.password || url.port || url.search || url.hash) return null;
    return normalizedPlaylistId(url.pathname.slice(1));
  } catch {
    return null;
  }
}

export function songShareUrl(videoId: string, destination: ShareDestination): string {
  if (!videoIdPattern.test(videoId)) throw new Error("This song has no valid YouTube video ID.");
  switch (destination) {
    case "player": return `https://mudesigns.github.io/ytmd-lite/?song=${videoId}`;
    case "music": return `https://music.youtube.com/watch?v=${videoId}`;
    case "youtube": return `https://www.youtube.com/watch?v=${videoId}`;
  }
}

export function sharedSongId(value: string): string | null {
  try {
    const url = new URL(value);
    if (url.protocol !== "ytmd-lite:" || url.hostname !== "song" || url.username || url.password || url.port) return null;
    const id = url.pathname.slice(1);
    return videoIdPattern.test(id) ? id : null;
  } catch {
    return null;
  }
}
