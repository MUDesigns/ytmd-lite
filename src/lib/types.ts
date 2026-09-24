export type Panel =
  | "search"
  | "home"
  | "explore"
  | "library"
  | "queue"
  | "detail"
  | "lastfm"
  | "settings";

export type ArtistLink = {
  name: string;
  browseId?: string;
};

export type MusicItem = {
  album?: string;
  albumId?: string;
  type: "song" | "album" | "artist" | "playlist" | "shelf" | "mood";
  id: string;
  title: string;
  subtitle?: string;
  thumbnails: string[];
  browseId?: string;
  playlistId?: string;
  videoId?: string;
  params?: string;
  color?: string;
  items?: MusicItem[];
  /** Primary artist channel (UC…) when known */
  artistBrowseId?: string;
  artistLinks?: ArtistLink[];
};

export type BrowseResult = {
  title?: string | null;
  shelves: MusicItem[];
  items: MusicItem[];
  thumbnails?: string[];
  subtitle?: string;
  artistBrowseId?: string;
  artistLinks?: ArtistLink[];
  /** e.g. "1.2M monthly listeners" for artist pages */
  meta?: string;
  kind?: "album" | "playlist" | "artist" | "mood" | "collection";
  subscribed?: boolean;
  radioId?: string;
  genres?: string[];
  channelId?: string;
};

export type EngineStatus = {
  signedIn: boolean;
  authVisible: boolean;
  ready: boolean;
};

export type QueueItem = {
  autoplay?: boolean;
  album?: string;
  albumId?: string;
  videoId: string;
  title: string;
  author: string;
  thumbnails: string[];
  selected: boolean;
  channelId?: string;
};

export type PlayerState = {
  videoDetails: {
    id: string;
    title: string;
    author: string;
    album: string;
    albumId?: string;
    channelId?: string;
    durationSeconds?: number;
    thumbnails?: string[];
  } | null;
  trackState: string;
  playbackError?: string;
  videoProgress?: number;
  volume?: number;
  muted?: boolean;
  likeStatus?: string;
  queue?: QueueItem[];
  queueIndex?: number;
  playlistId?: string;
  shuffle?: boolean;
  repeat?: "off" | "one" | "all";
  stopAfterAlbum?: string;
  autoplay?: boolean;
  autoplayLoading?: boolean;
  autoplayError?: string;
};

export type LastFmStatus = {
  enabled: boolean;
  authenticated: boolean;
  username: string | null;
  signingIn: boolean;
  scrobblePercent: number;
  hasCredentials: boolean;
  lastError: string | null;
  nowPlaying: string | null;
  lastScrobble: string | null;
};

export type DiscordRpcStatus = {
  enabled: boolean;
  connected: boolean;
  lastError?: string | null;
};

export type DetailTarget = {
  browseId?: string;
  playlistId?: string;
  title?: string;
  params?: string;
};
