import type { PlayerState } from "./types";

export type MiniPlayerUpdate = { player: PlayerState; accent: string };
/** Keep the frequent mini-player update small, even with a large queue. */
export function miniPlayerUpdate(player: PlayerState, accent: string): MiniPlayerUpdate {
  return { accent, player: {
    videoDetails: player.videoDetails, trackState: player.trackState,
    videoProgress: player.videoProgress, playbackError: player.playbackError,
    volume: player.volume,
  } };
}
