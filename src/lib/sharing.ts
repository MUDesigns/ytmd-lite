export const shareDestinations = [
  { id: "player", label: "YTMD Lite" },
  { id: "music", label: "YouTube Music" },
  { id: "youtube", label: "YouTube" },
] as const;

export type ShareDestination = typeof shareDestinations[number]["id"];
const videoIdPattern = /^[A-Za-z0-9_-]{11}$/;

export function songShareUrl(videoId: string, destination: ShareDestination): string {
  if (!videoIdPattern.test(videoId)) throw new Error("This song has no valid YouTube video ID.");
  switch (destination) {
    case "player": return `ytmd-lite://song/${videoId}`;
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
