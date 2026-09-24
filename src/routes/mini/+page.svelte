<script lang="ts">
  import { onMount, tick } from "svelte";
  import { invoke } from "@tauri-apps/api/core";
  import { emitTo, listen } from "@tauri-apps/api/event";
  import { getCurrentWindow } from "@tauri-apps/api/window";
  import type { PlayerState } from "$lib/types";
  import type { MiniPlayerUpdate, MiniLikeRequest, MiniLikeResult } from "$lib/mini-player";
  import { applyAccent } from "$lib/accent";
  import { thumb, loadPlaylistOptions, addTrackToPlaylist, type PlaylistOption } from "$lib/api";
  import VolumeValue from "$lib/ui/VolumeValue.svelte";
  let player = $state<PlayerState>({ videoDetails: null, trackState: "Unknown" });
  let pinned = $state(true);
  let error = $state("");
  let connected = $state(false);
  let scrubbing = $state(false);
  let scrub = $state(0);
  let likeRequest = $state("");
  let likeTimer: ReturnType<typeof setTimeout> | undefined;
  let notice = $state("");
  let playlistTrack = $state<PlayerState["videoDetails"]>(null);
  let playlists = $state<PlaylistOption[]>([]);
  let playlistId = $state("");
  let playlistLoading = $state(false);
  let playlistAdding = $state(false);
  let playlistError = $state("");
  let playlistLoaded = $state(false);
  let playlistRequest = 0;
  let playlistSelect = $state<HTMLSelectElement>();
  let playlistButton = $state<HTMLButtonElement>();
  const duration = $derived(player.videoDetails?.durationSeconds || 0);
  const progress = $derived(scrubbing ? scrub : player.videoProgress || 0);
  const art = $derived(thumb(player.videoDetails, "", 64));
  const playing = $derived(player.trackState === "Playing");
  const liked = $derived(player.likeStatus === "LIKE");
  async function like() {
    const videoId = player.videoDetails?.id;
    if (!videoId || likeRequest) return;
    error = "";
    notice = "";
    const requestId = crypto.randomUUID();
    likeRequest = requestId;
    likeTimer = setTimeout(() => {
      likeRequest = "";
      error = "Like update timed out. Check your connection.";
    }, 30000);
    try {
      await emitTo("chrome", "mini-player-like", { requestId, videoId } satisfies MiniLikeRequest);
    } catch (e) {
      clearTimeout(likeTimer);
      likeRequest = "";
      error = String(e);
    }
  }
  async function fetchPlaylists() {
    const request = ++playlistRequest;
    playlistLoading = true;
    playlistLoaded = false;
    playlistError = "";
    try {
      const options = await loadPlaylistOptions();
      if (request !== playlistRequest) return;
      playlists = options;
      playlistId = "";
      playlistLoaded = true;
      playlistLoading = false;
      await tick();
      playlistSelect?.focus();
    } catch (e) {
      if (request === playlistRequest) playlistError = String(e);
    } finally {
      if (request === playlistRequest) playlistLoading = false;
    }
  }
  function openPlaylists() {
    if (!player.videoDetails) return;
    playlistTrack = player.videoDetails;
    notice = "";
    error = "";
    playlists = [];
    playlistId = "";
    void fetchPlaylists();
  }
  async function closePlaylists() {
    if (playlistAdding) return;
    ++playlistRequest;
    playlistTrack = null;
    await tick();
    playlistButton?.focus();
  }
  async function addToPlaylist() {
    const track = playlistTrack;
    const selected = playlists.find(p => p.playlistId === playlistId);
    if (!track || !selected || playlistAdding) return;
    playlistAdding = true;
    playlistError = "";
    try {
      await addTrackToPlaylist(selected.playlistId, track);
      notice = `Added to ${selected.title}`;
      playlistAdding = false;
      await closePlaylists();
    } catch (e) {
      playlistError = String(e);
    } finally { playlistAdding = false; }
  }
  function time(value: number) { return `${Math.floor(value / 60)}:${String(Math.floor(value % 60)).padStart(2, "0")}`; }
  async function command(command: string) {
    error = "";
    try { await invoke("media_control", { command }); } catch (e) { error = String(e); }
  }
  async function restore() {
    try { await invoke("close_mini_player"); } catch (e) { error = String(e); }
  }
  async function pin() {
    try { await invoke("pin_mini_player", { pinned: !pinned }); pinned = !pinned; }
    catch (e) { error = String(e); }
  }
  async function drag(e: MouseEvent) {
    if (e.button !== 0 || (e.target as HTMLElement).closest("button")) return;
    try { await getCurrentWindow().startDragging(); } catch (e) { error = String(e); }
  }
  onMount(() => {
    let disposed = false;
    const unlisteners: Array<() => void> = [];
    void (async () => {
      try {
        const stop = await listen<MiniPlayerUpdate>("mini-player-state", ({ payload }) => {
          if (player.videoDetails?.id !== payload.player.videoDetails?.id) notice = "";
          player = payload.player; applyAccent(payload.accent); connected = true;
        });
        if (disposed) { stop(); return; }
        unlisteners.push(stop);
        const stopResult = await listen<MiniLikeResult>("mini-player-like-result", ({ payload }) => {
          if (payload.requestId !== likeRequest) return;
          clearTimeout(likeTimer);
          likeRequest = "";
          error = payload.error || "";
        });
        if (disposed) { stopResult(); return; }
        unlisteners.push(stopResult);
        await emitTo("chrome", "mini-player-ready", null);
      } catch (e) { error = String(e); }
    })();
    return () => { disposed = true; ++playlistRequest; clearTimeout(likeTimer); unlisteners.forEach(stop => stop()); };
  });
</script>

<svelte:head><title>YTMD Lite — Mini player</title></svelte:head>
<svelte:window onkeydown={(e) => { if (e.key === "Escape" && playlistTrack) { e.preventDefault(); void closePlaylists(); } }} />
<main class="mini-player">
  <div class="mini-titlebar" role="toolbar" tabindex="-1" aria-label="Mini player window" onmousedown={drag}>
    <span class="mini-brand">~/music</span>
    <button title={pinned ? "Turn off always on top" : "Keep always on top"} aria-label="Always on top" aria-pressed={pinned} class:active={pinned} onclick={pin}><span class="material-symbols-outlined">push_pin</span></button>
    <button title="Return to full player" aria-label="Return to full player" onclick={restore}><span class="material-symbols-outlined">open_in_full</span></button>
    <button title="Close mini player" aria-label="Close mini player" onclick={restore}><span class="material-symbols-outlined">close</span></button>
  </div>
  {#if playlistTrack}
    <form class="playlist-picker" aria-label="Add to playlist" onsubmit={(e) => { e.preventDefault(); void addToPlaylist(); }}>
      <div class="picker-heading"><button type="button" aria-label="Back to playback" title="Back to playback" disabled={playlistAdding} onclick={closePlaylists}>‹</button><strong>Add to playlist</strong></div>
      <div class="picker-track" title={playlistTrack.title}>{playlistTrack.title}</div>
      {#if playlistLoading}<p role="status">Loading playlists…</p>
      {:else if playlistLoaded && !playlists.length}<p>No playlists available. Create one in YouTube Music first.</p>
      {:else if playlistLoaded}
        <select bind:this={playlistSelect} aria-label="Playlist" bind:value={playlistId} disabled={playlistAdding}>
          <option value="" disabled>Choose a playlist</option>
          {#each playlists as playlist (playlist.playlistId)}<option value={playlist.playlistId}>{playlist.title}</option>{/each}
        </select>
      {/if}
      {#if playlistError}<p class="picker-error" role="alert">{playlistError}</p>{/if}
      <div class="picker-actions">
        {#if playlistError && !playlistLoaded}<button type="button" onclick={fetchPlaylists}>Retry</button>{/if}
        {#if playlistLoaded && playlists.length}<button type="submit" disabled={!playlistId || playlistAdding}>{playlistAdding ? "Adding…" : "Add song"}</button>{/if}
      </div>
    </form>
  {:else}
  <div class="mini-track">
    <div class="mini-art">{#if art}<img src={art} alt="" decoding="async" referrerpolicy="no-referrer" />{:else}<span class="material-symbols-outlined">music_note</span>{/if}</div>
    <div class="mini-meta"><div class="mini-song" title={player.videoDetails?.title}>{player.videoDetails?.title || "Nothing playing"}</div><div class="mini-artist">{player.videoDetails?.author || "Choose music in the full player"}</div><div class="mini-state" role="status" title={error || notice || player.playbackError}>{error || notice || player.playbackError || (!connected ? "Connecting…" : player.trackState === "Buffering" ? "Buffering…" : playing ? "Playing" : player.videoDetails ? "Paused" : "Ready")}</div></div>
  </div>
  <div class="mini-seek"><span>{time(progress)}</span><input aria-label="Seek" type="range" min="0" max={duration || 1} step="1" value={progress} disabled={!duration} oninput={(e) => { scrubbing = true; scrub = Number(e.currentTarget.value); }} onchange={(e) => { void command(`seek:${e.currentTarget.value}`); scrubbing = false; }} /><span>{time(duration)}</span></div>
  <div class="mini-controls">
    <button class="mini-action" class:active={liked} aria-label={liked ? "Unlike song" : "Like song"} title={liked ? "Unlike song" : "Like song"} aria-pressed={liked} aria-busy={!!likeRequest} disabled={!connected || !player.videoDetails || !!likeRequest} onclick={like}><span class="material-symbols-outlined">{liked ? "favorite" : "favorite_border"}</span></button>
    <button aria-label="Previous track" title="Previous track" disabled={!player.videoDetails} onclick={() => command("previous")}>|&lt;</button>
    <button class="mini-play" aria-label={playing ? "Pause" : "Play"} title={playing ? "Pause" : "Play"} disabled={!player.videoDetails} onclick={() => command("playPause")}>{playing ? "pause" : "play"}</button>
    <button aria-label="Next track" title="Next track" disabled={!player.videoDetails} onclick={() => command("next")}> &gt;|</button>
    <button class="mini-action" bind:this={playlistButton} aria-label="Add to playlist" title="Add to playlist" disabled={!connected || !player.videoDetails} onclick={openPlaylists}><span class="material-symbols-outlined">playlist_add</span></button>
  </div>
  {/if}
  <div class="mini-volume">
    <span class="volume-icon" aria-hidden="true">vol</span><input aria-label="Volume" type="range" min="0" max="100" value={player.volume ?? 100} oninput={(e) => command(`volume:${e.currentTarget.value}`)} /><VolumeValue value={player.volume ?? 100} onchange={(value) => command(`volume:${value}`)} percent />
  </div>
</main>

<style>
  .mini-player { height: 100%; width: 100%; border: 1px solid var(--md-sys-color-outline); background: var(--md-sys-color-surface-container-lowest); display: flex; flex-direction: column; font-family: "Cascadia Code", "Consolas", monospace; }
  .mini-titlebar { display: flex; align-items: center; height: 28px; flex-shrink: 0; padding: 0 2px 0 8px; background: var(--md-sys-color-surface-container-lowest); border-bottom: var(--hairline); cursor: grab; }
  .mini-brand { flex: 1; color: var(--md-sys-color-primary); font-size: 11px; }
  button { display: grid; place-items: center; padding: 0; width: 30px; height: 28px; font: inherit; border-radius: 0; }
  button:focus-visible, input:focus-visible { outline: 1px solid var(--md-sys-color-primary); outline-offset: 2px; }
  button:hover { background: var(--md-sys-color-surface-container-high); }
  button.active { color: var(--md-sys-color-primary); }
  .mini-action.active .material-symbols-outlined { font-variation-settings: "FILL" 1; }
  .material-symbols-outlined { font-size: 19px; }
  .mini-titlebar .material-symbols-outlined { font-size: 16px; }
  .mini-track { display: flex; gap: 6px; align-items: center; padding: 6px 8px 4px; min-height: 56px; }
  .mini-art { width: 26px; height: 32px; flex: 0 0 26px; display: grid; place-items: center; background: var(--md-sys-color-surface-container); border: var(--hairline); }
  img { width: 100%; height: 100%; object-fit: cover; }
  .mini-meta { min-width: 0; }
  .mini-song::before { content: "> "; color: var(--md-sys-color-primary); }
  .mini-artist::before { content: "by "; opacity: .6; }
  .mini-state::before { content: "[ "; }
  .mini-state::after { content: " ] ▌"; }
  .mini-song { font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .mini-artist { color: var(--md-sys-color-on-surface-variant); font-size: 11px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .mini-state { color: var(--md-sys-color-primary); font-size: 10px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; margin-top: 2px; }
  .mini-seek { display: flex; align-items: center; gap: 6px; padding: 0 10px; font-size: 10px; color: var(--md-sys-color-on-surface-variant); }
  input[type=range] { appearance: none; background: transparent; min-width: 0; height: 16px; cursor: pointer; }
  input[type=range]::-webkit-slider-runnable-track { height: 6px; background: repeating-linear-gradient(to right, var(--md-sys-color-outline) 0 3px, transparent 3px 5px); }
  input[type=range]::-webkit-slider-thumb { appearance: none; width: 7px; height: 12px; margin-top: -3px; border-radius: 0; background: var(--md-sys-color-primary); }
  input:disabled { opacity: .4; cursor: default; }
  .mini-seek input { flex: 1; }
  .mini-controls { display: flex; align-items: center; gap: 6px; padding: 4px 8px; }
  .mini-controls button { height: 28px; flex: 1; border: 1px solid var(--md-sys-color-outline); font-size: 11px; }
  .mini-controls .mini-play { flex: 1.5; color: var(--md-sys-color-primary); border-color: var(--md-sys-color-primary); }
  .mini-controls { gap: 4px; }
  .mini-controls .mini-action { flex: 0 0 28px; }
  .playlist-picker { flex: 1; min-height: 0; overflow-y: auto; padding: 3px 8px; display: flex; flex-direction: column; gap: 4px; font-size: 10px; }
  .picker-heading { display: flex; align-items: center; gap: 4px; flex-shrink: 0; }
  .picker-heading button { width: 22px; height: 20px; font-size: 20px; }
  .picker-heading strong { color: var(--md-sys-color-primary); font-weight: 500; }
  .picker-track { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; flex-shrink: 0; }
  .playlist-picker select { width: 100%; min-height: 26px; flex-shrink: 0; color: var(--md-sys-color-on-surface); background: var(--md-sys-color-surface-container); border: var(--hairline); }
  .playlist-picker p { margin: 0; overflow-wrap: anywhere; }
  .picker-actions { display: flex; justify-content: flex-end; flex-shrink: 0; }
  .picker-actions button { width: auto; height: 24px; padding: 0 8px; border: var(--hairline); color: var(--md-sys-color-primary); }
  .mini-volume { display: flex; align-items: center; gap: 8px; padding: 0 8px 6px; font-size: 10px; color: var(--md-sys-color-on-surface-variant); }
  .mini-volume input { flex: 1; width: 0; }
</style>
