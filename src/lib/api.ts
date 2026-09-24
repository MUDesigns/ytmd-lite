import type { ArtistLink, BrowseResult, MusicItem } from "./types";

export const API_BASE = "http://127.0.0.1:9847";

const RECENT_KEY = "ytmd.recentSearches";
const RECENT_MAX = 8;

const MOOD_COLORS = [
  "#e57373",
  "#f06292",
  "#ba68c8",
  "#9575cd",
  "#7986cb",
  "#64b5f6",
  "#4fc3f7",
  "#4dd0e1",
  "#4db6ac",
  "#81c784",
  "#aed581",
  "#ffb74d",
  "#ff8a65",
  "#a1887f",
];

async function api<T = unknown>(path: string, init?: RequestInit): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`${API_BASE}${path}`, init);
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    if (/failed to fetch|networkerror|load failed/i.test(msg)) {
      throw new Error(
        "Catalog API unreachable on :9847 — restart the app (it should start ytmd-backend automatically)",
      );
    }
    throw e instanceof Error ? e : new Error(msg);
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const err =
      (data as { error?: string }).error ||
      (data as { message?: string }).message ||
      `HTTP ${res.status}`;
    throw new Error(err);
  }
  if (data && typeof data === "object" && "error" in data && (data as { error: unknown }).error) {
    throw new Error(String((data as { error: unknown }).error));
  }
  return data as T;
}

function artistsLine(artists: unknown): string {
  if (typeof artists === "string") return artists;
  if (Array.isArray(artists)) {
    return artists
      .map((a) => (typeof a === "string" ? a : (a as { name?: string })?.name || ""))
      .filter(Boolean)
      .join(", ");
  }
  return "";
}

export function parseArtistLinks(raw: Record<string, unknown>): ArtistLink[] {
  const fromLinks = raw.artistLinks;
  if (Array.isArray(fromLinks) && fromLinks.length) {
    return fromLinks
      .map((a): ArtistLink | null => {
        if (!a || typeof a !== "object") return null;
        const o = a as { name?: string; browseId?: string; id?: string };
        const name = String(o.name || "").trim();
        if (!name) return null;
        const browseId = String(o.browseId || o.id || "").trim() || undefined;
        return { name, browseId } satisfies ArtistLink;
      })
      .filter((a): a is ArtistLink => !!a);
  }

  const primary =
    (raw.artistBrowseId ? String(raw.artistBrowseId) : undefined) ||
    (typeof raw.browseId === "string" && String(raw.browseId).startsWith("UC")
      ? String(raw.browseId)
      : undefined);

  if (Array.isArray(raw.artists) && raw.artists.some((a) => a && typeof a === "object")) {
    return raw.artists
      .map((a, i): ArtistLink | null => {
        if (!a || typeof a !== "object") return null;
        const o = a as { name?: string; id?: string; browseId?: string };
        const name = String(o.name || "").trim();
        if (!name) return null;
        const browseId =
          String(o.browseId || o.id || "").trim() || (i === 0 ? primary : undefined);
        return { name, browseId } satisfies ArtistLink;
      })
      .filter((a): a is ArtistLink => !!a);
  }

  const line = artistsLine(raw.artists);
  if (!line) return [];
  return line.split(",").map((name, i) => ({
    name: name.trim(),
    browseId: i === 0 ? primary : undefined,
  })).filter((a) => a.name);
}

function colorFor(title: string): string {
  let h = 0;
  for (let i = 0; i < title.length; i++) h = (h * 31 + title.charCodeAt(i)) >>> 0;
  return MOOD_COLORS[h % MOOD_COLORS.length];
}

function pickThumbUrl(raw: Record<string, unknown>): string {
  if (typeof raw.thumbnail === "string" && raw.thumbnail) return raw.thumbnail;
  if (raw.thumbnail && typeof raw.thumbnail === "object") {
    const u = (raw.thumbnail as { url?: string }).url;
    if (u) return u;
  }
  const list = raw.thumbnails;
  if (!Array.isArray(list) || !list.length) return "";
  // Prefer last (usually largest) when entries are strings or {url,width}
  for (let i = list.length - 1; i >= 0; i--) {
    const t = list[i];
    if (typeof t === "string" && t) return t;
    if (t && typeof t === "object" && typeof (t as { url?: string }).url === "string") {
      return (t as { url: string }).url;
    }
  }
  return "";
}

/** Route Google/YT thumbs through the local proxy (Referer from localhost is blocked). */
export function proxiedUrl(url: string | null | undefined, size?: number): string {
  if (!url) return "";
  if (
    url.startsWith("data:") ||
    url.startsWith("blob:") ||
    url.startsWith(API_BASE) ||
    url.includes("/imgproxy?")
  ) {
    return url;
  }
  const bounded = size !== undefined && Number.isFinite(size) && size > 0;
  if (bounded) {
    // Preserve Google's crop/format suffix; only resize known thumbnail hosts.
    // Other URLs keep their native variant, without the proxy's original-size upgrade.
    try {
      const parsed = new URL(url);
      if (/^(?:lh\d+\.googleusercontent\.com|yt3\.(?:ggpht\.com|googleusercontent\.com))$/.test(parsed.hostname)) {
        const pixels = Math.min(1024, Math.ceil(size));
        parsed.pathname = parsed.pathname.replace(/=w\d+-h\d+/, `=w${pixels}-h${pixels}`);
        url = parsed.href;
      }
    } catch { /* Let the existing proxy handle malformed URLs. */ }
  }
  return `${API_BASE}/imgproxy?hq=${bounded ? 0 : 1}&url=${encodeURIComponent(url)}`;
}

/** Map Kodama API item → MusicItem */
export function mapItem(raw: Record<string, unknown>): MusicItem {
  const typeRaw = String(raw.type || "").toLowerCase();
  const browseId = raw.browseId ? String(raw.browseId) : undefined;
  let playlistId = raw.playlistId ? String(raw.playlistId) : undefined;
  if (playlistId?.startsWith("VL")) playlistId = playlistId.slice(2);
  if (!playlistId && browseId?.startsWith("VL")) playlistId = browseId.slice(2);
  if (!playlistId && browseId === "LM") playlistId = "LM";
  const videoId = raw.videoId ? String(raw.videoId) : undefined;

  let type: MusicItem["type"] = "song";
  if (playlistId === "LM") type = "playlist";
  else if (typeRaw === "album" || typeRaw === "single" || typeRaw === "ep") type = "album";
  else if (typeRaw === "artist") type = "artist";
  else if (typeRaw === "playlist" || typeRaw === "podcast") type = "playlist";
  else if (typeRaw === "mood") type = "mood";
  else if (browseId?.startsWith("UC")) type = "artist";
  else if (browseId?.startsWith("VL") || playlistId) type = "playlist";
  else if (
    browseId?.startsWith("MPRE") ||
    browseId?.startsWith("OLAK5") ||
    browseId?.startsWith("MPSP")
  ) {
    type = "album";
  } else if (typeRaw === "song" || typeRaw === "video" || videoId) type = "song";
  else if (browseId) type = "album";

  // Collections must open a detail page — don't treat a stray videoId as "just a song".
  const isCollection =
    type === "album" || type === "playlist" || type === "artist" || type === "mood" || !!playlistId;
  const playableVideoId = isCollection ? undefined : videoId;

  const thumb = pickThumbUrl(raw);
  const title = String(raw.title || raw.artist || "Untitled");

  const artistLinks = parseArtistLinks(raw);
  const artistBrowseId =
    (raw.artistBrowseId ? String(raw.artistBrowseId) : undefined) ||
    artistLinks.find((a) => a.browseId)?.browseId ||
    undefined;

  const subtitle =
    (typeof raw.subtitle === "string" && raw.subtitle) ||
    artistsLine(raw.artists) ||
    artistLinks.map((a) => a.name).join(", ") ||
    (raw.year ? String(raw.year) : "") ||
    (raw.count ? `${raw.count} tracks` : "") ||
    undefined;

  return {
    type,
    id: playableVideoId || browseId || playlistId || title,
    title,
    subtitle,
    thumbnails: thumb ? [thumb] : [],
    browseId,
    playlistId,
    videoId: playableVideoId,
    params: raw.params ? String(raw.params) : undefined,
    color: typeof raw.color === "string" ? raw.color : undefined,
    artistBrowseId,
    artistLinks: artistLinks.length ? artistLinks : undefined,
    album: typeof raw.album === "string" ? raw.album : undefined,
    albumId: typeof raw.albumBrowseId === "string" ? raw.albumBrowseId : undefined,
  };
}

function mapTrack(t: Record<string, unknown>): MusicItem {
  return mapItem({
    type: "song",
    videoId: t.videoId,
    title: t.title,
    artists: t.artists,
    artistBrowseId: t.artistBrowseId,
    artistLinks: t.artistLinks,
    album: t.album,
    albumBrowseId: t.albumBrowseId,
    thumbnail: t.thumbnail,
  });
}

export async function validateAuth(): Promise<{
  valid: boolean | null;
  profile?: string;
  type?: string;
  reason?: string;
}> {
  try {
    const result = await api<{ valid: boolean; profile?: string; type?: string; reason?: string }>("/auth/validate");
    if (typeof result?.valid !== "boolean") throw new Error("Invalid auth response");
    return result;
  } catch {
    // A failed check tells us nothing about the saved Google session.
    return { valid: null, reason: "api_unreachable" };
  }
}

export async function loadHome(): Promise<BrowseResult> {
  const data = await api<{ sections?: Array<{ title?: string; items?: Record<string, unknown>[] }> }>(
    "/home",
  );
  const shelves: MusicItem[] = (data.sections || []).map((sec, i) => ({
    type: "shelf",
    id: `home-${i}-${sec.title || ""}`,
    title: sec.title || "Shelf",
    thumbnails: [],
    items: (sec.items || []).map(mapItem),
  }));
  return { title: "Home", shelves, items: [] };
}

/** Build discovery from personal seeds, never from unrelated genre charts. */
export async function loadExplore(heardIds: string[] = []): Promise<BrowseResult> {
  const heard = new Set(heardIds);
  let seeds = [...heard].reverse().slice(0, 3).map(videoId => ({ videoId, title: "" }));
  const fromLikes = !seeds.length;
  if (fromLikes) {
    const liked = await api<{ tracks?: Record<string, unknown>[] }>("/liked?limit=30", { signal: AbortSignal.timeout(15000) });
    seeds = uniqueItems((liked.tracks || []).map(mapTrack)).filter(t => t.videoId)
      .slice(0, 3).map(t => ({ videoId: t.videoId!, title: t.title }));
  }
  if (!seeds.length) return {
    title: "Discover", shelves: [], items: [],
    subtitle: "Play some songs or like a few favorites to build your Discover page.",
  };

  const radios = await Promise.allSettled(seeds.map(seed => loadSongRadio(seed.videoId)));
  if (radios.every(result => result.status === "rejected")) {
    throw new Error("Could not load recommendations. Try refreshing Discover.");
  }
  const shelves: MusicItem[] = [];
  const seen = new Set([...heard, ...seeds.map(seed => seed.videoId)]);
  const artists = new Map<string, string>();
  radios.forEach((result, i) => {
    if (result.status !== "fulfilled") return;
    const seed = seeds[i];
    const source = result.value.find(t => t.videoId === seed.videoId);
    if (source?.artistBrowseId) artists.set(source.artistBrowseId, source.subtitle || "this artist");
    const items = result.value.filter(item => item.videoId && !seen.has(item.videoId)).slice(0, 12);
    items.forEach(item => seen.add(item.videoId!));
    if (items.length) shelves.push({
      type: "shelf", id: `discover-${seed.videoId}`, thumbnails: [], items,
      title: `Because you ${fromLikes ? "like" : "played"} ${source?.title || seed.title || "a recent favorite"}`,
    });
  });
  const related = await Promise.allSettled([...artists].map(async ([browseId, name]) => {
    const data = await api<{ related?: Record<string, unknown>[] }>(`/artist/${encodeURIComponent(browseId)}`, { signal: AbortSignal.timeout(15000) });
    return { name, items: uniqueItems((data.related || []).map(t => mapItem({ ...t, type: "artist" }))) };
  }));
  const seenArtists = new Set(artists.keys());
  related.forEach((result, i) => {
    if (result.status !== "fulfilled") return;
    const items = result.value.items.filter(t => t.browseId && !seenArtists.has(t.browseId)).slice(0, 8);
    items.forEach(item => seenArtists.add(item.browseId!));
    if (items.length) shelves.push({ type: "shelf", id: `discover-artists-${i}`, title: `Artists related to ${result.value.name}`, thumbnails: [], items });
  });
  return {
    title: "Discover", shelves,
    // Queue recommendations in their visible shelf order, excluding artist cards.
    items: shelves.flatMap(shelf => shelf.items || []).filter(item => item.type === "song" && item.videoId),
    subtitle: shelves.length
      ? `Inspired by your ${fromLikes ? "liked songs" : "recent listening on this device"}. Tracks already heard here are hidden.`
      : "No new recommendations from these favorites yet. Try listening to another artist, then refresh.",
  };
}

function uniqueItems(items: MusicItem[]): MusicItem[] {
  const seen = new Set<string>();
  return items.filter(item => {
    if (seen.has(item.id)) return false;
    seen.add(item.id);
    return true;
  });
}

export async function loadSongRadio(videoId: string, signal?: AbortSignal): Promise<MusicItem[]> {
  const timeout = AbortSignal.timeout(15000);
  const data = await api<{ tracks?: Record<string, unknown>[] }>(
    `/radio/seed?videoId=${encodeURIComponent(videoId)}`, { signal: signal ? AbortSignal.any([signal, timeout]) : timeout },
  );
  return uniqueItems((data.tracks || []).map(mapTrack).filter(t => t.videoId));
}

export async function loadArtistRadio(browseId: string): Promise<MusicItem[]> {
  const artist = await browseDetail({ browseId });
  let tracks: MusicItem[];
  if (artist.radioId) {
    const data = await api<{ tracks?: Record<string, unknown>[] }>(
      `/radio/${encodeURIComponent(artist.radioId)}`, { signal: AbortSignal.timeout(15000) },
    );
    tracks = uniqueItems((data.tracks || []).map(mapTrack).filter(t => t.videoId));
  } else {
    const seed = artist.items.find(t => t.videoId);
    tracks = seed ? await loadSongRadio(seed.videoId!) : [];
  }
  if (!tracks.length) throw new Error("No radio tracks are available for this artist. Try another artist.");
  return tracks;
}

export async function loadLibrary(): Promise<BrowseResult> {
  const [playlists, albums, artists] = await Promise.all([
    api<{ playlists?: Record<string, unknown>[] }>("/library/playlists").catch(() => ({
      playlists: [],
    })),
    api<{ albums?: Record<string, unknown>[] }>("/library/albums").catch(() => ({ albums: [] })),
    api<{ artists?: Record<string, unknown>[] }>("/library/artists").catch(() => ({ artists: [] })),
  ]);

  const shelves: MusicItem[] = [];
  const plItems = (playlists.playlists || []).map((p) =>
    mapItem({ ...p, type: "playlist", title: p.title }),
  );
  // Liked Songs has its own API and may be absent from library playlists.
  // Load its tracks only when opened, using the backend's special LM route.
  if (!plItems.some((p) => p.playlistId === "LM")) {
    plItems.unshift(mapItem({ type: "playlist", playlistId: "LM", title: "Liked Songs" }));
  }
  if (plItems.length) {
    shelves.push({
      type: "shelf",
      id: "lib-playlists",
      title: "Playlists",
      thumbnails: [],
      items: plItems,
    });
  }
  const alItems = (albums.albums || []).map((a) => mapItem({ ...a, type: "album" }));
  if (alItems.length) {
    shelves.push({
      type: "shelf",
      id: "lib-albums",
      title: "Albums",
      thumbnails: [],
      items: alItems,
    });
  }
  const arItems = (artists.artists || []).map((a) =>
    mapItem({
      ...a,
      type: "artist",
      title: a.artist || a.title,
    }),
  );
  if (arItems.length) {
    shelves.push({
      type: "shelf",
      id: "lib-artists",
      title: "Artists",
      thumbnails: [],
      items: arItems,
    });
  }
  return { title: "Library", shelves, items: [] };
}

export type SearchFilter = "all" | "songs" | "artists" | "albums" | "playlists" | "library";

export async function search(
  query: string,
  filter: SearchFilter = "all",
): Promise<BrowseResult> {
  if (filter === "library") return searchLibrary(query);

  const params = new URLSearchParams({ q: query });
  if (filter !== "all") params.set("filter", filter);
  const data = await api<{ results?: Record<string, unknown>[] }>(`/search?${params}`);
  // Mixed search can repeat a top result in its song/video/album category.
  // Once flattened into one shelf, repeats would collide in the keyed grid.
  const seen = new Set<string>();
  const items = (data.results || []).map(mapItem).filter((item) => {
    if (seen.has(item.id)) return false;
    seen.add(item.id);
    return true;
  });
  const shelfTitle =
    filter === "all"
      ? "Results"
      : filter.charAt(0).toUpperCase() + filter.slice(1);
  return {
    title: "Search",
    shelves: items.length
      ? [{ type: "shelf", id: `search-${filter}`, title: shelfTitle, thumbnails: [], items }]
      : [],
    items,
  };
}

/** Filter the signed-in library (playlists / albums / artists / liked) by name. */
export async function searchLibrary(query: string): Promise<BrowseResult> {
  const q = query.trim().toLowerCase();
  if (!q) return { title: "Library", shelves: [], items: [] };

  const match = (item: MusicItem) =>
    item.title.toLowerCase().includes(q) ||
    (item.subtitle || "").toLowerCase().includes(q) ||
    (item.artistLinks || []).some((a) => a.name.toLowerCase().includes(q));

  const [lib, liked] = await Promise.all([
    loadLibrary(),
    api<{ tracks?: Record<string, unknown>[] }>("/liked?limit=500").catch(() => ({ tracks: [] })),
  ]);

  const shelves: MusicItem[] = [];
  const likedItems = (liked.tracks || []).map(mapTrack).filter(match);
  if (likedItems.length) {
    shelves.push({
      type: "shelf",
      id: "lib-search-liked",
      title: "Liked songs",
      thumbnails: [],
      items: likedItems,
    });
  }
  for (const shelf of lib.shelves || []) {
    const items = (shelf.items || []).filter(match);
    if (items.length) {
      shelves.push({ ...shelf, id: `lib-search-${shelf.id}`, items });
    }
  }
  return {
    title: "Library",
    shelves,
    items: shelves.flatMap((s) => s.items || []),
  };
}

export async function searchSuggestions(query: string) {
  return api<{ suggestions: string[] }>(
    `/search/suggestions?q=${encodeURIComponent(query)}`,
  );
}

/** Look up an artist channel id by name when YouTube omitted it from the shelf/track. */
export async function resolveArtist(name: string): Promise<ArtistLink | null> {
  const q = name.trim();
  if (!q) return null;
  const data = await api<{ results?: Record<string, unknown>[] }>(
    `/search?q=${encodeURIComponent(q)}&filter=artists`,
  );
  for (const raw of data.results || []) {
    const item = mapItem({ ...raw, type: "artist" });
    const browseId = item.browseId;
    if (browseId?.startsWith("UC")) {
      return { name: item.title || q, browseId };
    }
  }
  // Fallback: mixed search may still surface the artist card
  const mixed = await api<{ results?: Record<string, unknown>[] }>(
    `/search?q=${encodeURIComponent(q)}`,
  );
  for (const raw of mixed.results || []) {
    const item = mapItem(raw);
    if (item.type === "artist" && item.browseId?.startsWith("UC")) {
      return { name: item.title || q, browseId: item.browseId };
    }
  }
  return null;
}

export async function likeSong(
  videoId: string,
  rating: "LIKE" | "INDIFFERENT" | "DISLIKE" = "LIKE",
  meta?: { title?: string; artists?: string; album?: string; thumbnail?: string },
) {
  return api<{ ok: boolean; rating: string }>(`/like/${encodeURIComponent(videoId)}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ rating, ...meta }),
  });
}

export type PlaylistOption = { playlistId: string; title: string };

export async function loadPlaylistOptions(): Promise<PlaylistOption[]> {
  const data = await api<{ playlists?: Array<PlaylistOption & { isEditable?: boolean }> }>("/library/playlists");
  const seen = new Set<string>();
  return (data.playlists || []).flatMap(p => {
    const playlistId = p.playlistId?.replace(/^VL/, "");
    if (!playlistId || playlistId === "LM" || p.isEditable === false || seen.has(playlistId)) return [];
    seen.add(playlistId);
    return [{ playlistId, title: p.title || "Untitled playlist" }];
  });
}

export async function addTrackToPlaylist(playlistId: string, track: {
  id: string; title: string; author?: string; album?: string; thumbnails?: string[];
}) {
  const result = await api<{ ok: boolean }>(`/playlist/${encodeURIComponent(playlistId)}/add`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ videoIds: [track.id], tracks: [{
      videoId: track.id, title: track.title, artists: track.author || "",
      album: track.album || "", thumbnail: track.thumbnails?.[0] || "",
    }] }),
  });
  if (!result.ok) throw new Error("Could not add this song to the playlist.");
}

export async function setArtistSubscribed(
  browseId: string,
  subscribed: boolean,
  channelId?: string,
) {
  const path = subscribed
    ? `/artist/${encodeURIComponent(browseId)}/subscribe`
    : `/artist/${encodeURIComponent(browseId)}/unsubscribe`;
  return api<{ ok: boolean }>(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ channelId: channelId || browseId }),
  });
}

export async function getLikedIds(): Promise<string[]> {
  try {
    const data = await api<{ ids?: string[] }>("/liked/ids");
    return data.ids || [];
  } catch {
    return [];
  }
}

export async function browseDetail(opts: {
  browseId?: string;
  playlistId?: string;
  params?: string;
  title?: string;
}): Promise<BrowseResult> {
  if (opts.params) {
    const data = await api<Record<string, unknown>[] | { error?: string }>(
      `/mood/playlists?params=${encodeURIComponent(opts.params)}`,
    );
    const list = Array.isArray(data) ? data : [];
    return { title: opts.title || "Mood", shelves: [], items: list.map(mapItem) };
  }

  if (opts.playlistId || opts.browseId?.startsWith("VL") || opts.browseId === "LM") {
    const rawPid =
      opts.playlistId ||
      (opts.browseId!.startsWith("VL") ? opts.browseId!.slice(2) : opts.browseId!);
    const pid = rawPid.startsWith("VL") ? rawPid.slice(2) : rawPid;
    // Radio / auto-mix ids (Supermix, Archive Mix, artist radio, …) are infinite
    // watch playlists — get_playlist hangs; use /radio (get_watch_playlist) instead.
    const isRadioMix = pid.startsWith("RD");
    const data = await api<{
      title?: string;
      tracks?: Record<string, unknown>[];
      thumbnails?: unknown;
      thumbnail?: unknown;
      author?: string;
      artists?: unknown;
      artistBrowseId?: string;
      artistLinks?: unknown;
      error?: string;
    }>(`${isRadioMix ? "/radio" : "/playlist"}/${encodeURIComponent(pid)}`);
    if (data.error) throw new Error(data.error);
    const items = (data.tracks || []).map(mapTrack);
    const cover =
      pickThumbUrl(data as Record<string, unknown>) ||
      items.find((t) => t.thumbnails?.[0])?.thumbnails?.[0] ||
      "";
    const artistLinks = parseArtistLinks(data as Record<string, unknown>);
    return {
      title: data.title || opts.title || (isRadioMix ? "Mix" : "Playlist"),
      subtitle: artistsLine(data.artists) || (typeof data.author === "string" ? data.author : undefined),
      shelves: [],
      items,
      thumbnails: cover ? [cover] : [],
      artistBrowseId: data.artistBrowseId ? String(data.artistBrowseId) : artistLinks[0]?.browseId,
      artistLinks: artistLinks.length ? artistLinks : undefined,
    };
  }

  const bid = opts.browseId || "";
  if (bid.startsWith("UC")) {
    const data = await api<{
      name?: string;
      tracks?: Record<string, unknown>[];
      albums?: Record<string, unknown>[];
      singles?: Record<string, unknown>[];
      thumbnails?: unknown;
      thumbnail?: unknown;
      monthlyListeners?: string;
      subscribers?: string;
      subscribed?: boolean;
      channelId?: string;
      radioId?: string;
      related?: Record<string, unknown>[];
    }>(`/artist/${encodeURIComponent(bid)}`, { signal: AbortSignal.timeout(15000) });
    const tracks = (data.tracks || []).map(mapTrack);
    const releases: Record<string, MusicItem[]> = { albums: [], eps: [], singles: [], other: [] };
    for (const [items, fallback] of [[data.albums || [], "albums"], [data.singles || [], "other"]] as const) {
      for (const release of items) {
        const label = String(release.releaseType || release.type || "").trim().toLowerCase();
        const group = label === "ep" ? "eps" : label === "single" ? "singles" : label === "album" ? "albums" : fallback;
        releases[group].push(mapItem({ ...release, type: "album" }));
      }
    }
    const shelves: MusicItem[] = [];
    for (const [group, title] of [["albums", "Albums"], ["eps", "EPs"], ["singles", "Singles"], ["other", "Singles & EPs"]]) {
      if (releases[group].length) {
        shelves.push({
          type: "shelf",
          id: `artist-${group}`,
          title,
          thumbnails: [],
          items: releases[group],
        });
      }
    }
    const related = uniqueItems((data.related || []).map(t => mapItem({ ...t, type: "artist" }))).filter(t => t.browseId);
    if (related.length) shelves.push({ type: "shelf", id: "artist-related", title: "Fans might also like", thumbnails: [], items: related });
    const cover =
      pickThumbUrl(data as Record<string, unknown>) ||
      tracks.find((t) => t.thumbnails?.[0])?.thumbnails?.[0] ||
      shelves.flatMap((s) => s.items || []).find((a) => a.thumbnails?.[0])?.thumbnails?.[0] ||
      "";
    const listeners = String(data.monthlyListeners || "").trim();
    const subs = String(data.subscribers || "").trim();
    let meta: string | undefined;
    if (listeners) {
      meta = /listener/i.test(listeners) ? listeners : `${listeners} monthly listeners`;
    } else if (subs) {
      meta = /subscriber/i.test(subs) ? subs : `${subs} subscribers`;
    }
    return {
      title: data.name || opts.title || "Artist",
      shelves,
      items: tracks,
      thumbnails: cover ? [cover] : [],
      meta,
      kind: "artist",
      radioId: data.radioId || undefined,
      subscribed: !!data.subscribed,
      channelId: data.channelId ? String(data.channelId) : bid,
    };
  }

  if (bid) {
    const data = await api<{
      title?: string;
      tracks?: Record<string, unknown>[];
      thumbnails?: unknown;
      thumbnail?: unknown;
      artists?: unknown;
      artistBrowseId?: string;
      artistLinks?: unknown;
    }>(`/album/${encodeURIComponent(bid)}`);
    const items = (data.tracks || []).map(t => mapTrack({ ...t, album: t.album || data.title || opts.title, albumBrowseId: t.albumBrowseId || bid }));
    const cover =
      pickThumbUrl(data as Record<string, unknown>) ||
      items.find((t) => t.thumbnails?.[0])?.thumbnails?.[0] ||
      "";
    const artistLinks = parseArtistLinks(data as Record<string, unknown>);
    return {
      title: data.title || opts.title || "Album",
      subtitle: artistsLine(data.artists) || artistLinks.map((a) => a.name).join(", "),
      shelves: [],
      items,
      thumbnails: cover ? [cover] : [],
      artistBrowseId: data.artistBrowseId ? String(data.artistBrowseId) : artistLinks[0]?.browseId,
      artistLinks: artistLinks.length ? artistLinks : undefined,
    };
  }

  throw new Error("Nothing to browse");
}

/** @deprecated use browseDetail */
export async function browse(browseId: string, params?: string) {
  return browseDetail({ browseId, params });
}

export function streamUrl(videoId: string) {
  return `${API_BASE}/audio-stream/${encodeURIComponent(videoId)}`;
}

export function thumb(item: { thumbnails?: string[] } | null | undefined, fallback = "", size?: number) {
  const list = item?.thumbnails;
  if (!list?.length) return fallback;
  const raw = list[list.length - 1] || list[0] || fallback;
  return proxiedUrl(typeof raw === "string" ? raw : "", size);
}

export function loadRecentSearches(): string[] {
  try {
    const raw = localStorage.getItem(RECENT_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.filter((x) => typeof x === "string").slice(0, RECENT_MAX) : [];
  } catch {
    return [];
  }
}

export function pushRecentSearch(query: string) {
  const q = query.trim();
  if (!q) return;
  const next = [q, ...loadRecentSearches().filter((x) => x.toLowerCase() !== q.toLowerCase())].slice(
    0,
    RECENT_MAX,
  );
  try {
    localStorage.setItem(RECENT_KEY, JSON.stringify(next));
  } catch {
    /* ignore */
  }
  return next;
}
