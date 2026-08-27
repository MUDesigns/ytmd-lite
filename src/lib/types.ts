export type Panel =
  | "home"
  | "explore"
  | "library"
  | "queue"
  | "detail"
  | "lastfm"
  | "settings";

export type MusicItem = {
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
};

export type BrowseResult = {
  title?: string | null;
  shelves: MusicItem[];
  items: MusicItem[];
  thumbnails?: string[];
  subtitle?: string;
};

export type EngineStatus = {
  signedIn: boolean;
  authVisible: boolean;
  ready: boolean;
};

export type QueueItem = {
  videoId: string;
  title: string;
  author: string;
  thumbnails: string[];
  selected: boolean;
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
  videoProgress?: number;
  volume?: number;
  muted?: boolean;
  likeStatus?: string;
  queue?: QueueItem[];
  queueIndex?: number;
  playlistId?: string;
  shuffle?: boolean;
  repeat?: "off" | "one" | "all";
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
