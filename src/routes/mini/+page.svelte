<script lang="ts">
  import { onMount } from "svelte";
  import { invoke } from "@tauri-apps/api/core";
  import { emitTo, listen } from "@tauri-apps/api/event";
  import { getCurrentWindow } from "@tauri-apps/api/window";
  import type { PlayerState } from "$lib/types";
  import type { MiniPlayerUpdate } from "$lib/mini-player";
  import { applyAccent } from "$lib/accent";
  import { thumb } from "$lib/api";
  let player = $state<PlayerState>({ videoDetails: null, trackState: "Unknown" });
  let pinned = $state(true);
  let error = $state("");
  let connected = $state(false);
  let scrubbing = $state(false);
  let scrub = $state(0);
  const duration = $derived(player.videoDetails?.durationSeconds || 0);
  const progress = $derived(scrubbing ? scrub : player.videoProgress || 0);
  const art = $derived(thumb(player.videoDetails));
  const playing = $derived(player.trackState === "Playing");
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
    let unlisten: (() => void) | undefined;
    void (async () => {
      try {
        const stop = await listen<MiniPlayerUpdate>("mini-player-state", ({ payload }) => {
          player = payload.player; applyAccent(payload.accent); connected = true;
        });
        if (disposed) { stop(); return; }
        unlisten = stop;
        await emitTo("chrome", "mini-player-ready", null);
      } catch (e) { error = String(e); }
    })();
    return () => { disposed = true; unlisten?.(); };
  });
</script>

<svelte:head><title>YTMD Lite — Mini player</title></svelte:head>
<main class="mini-player">
  <div class="mini-titlebar" role="toolbar" tabindex="-1" aria-label="Mini player window" onmousedown={drag}>
    <span class="mini-brand">ytmd-lite <span>/ mini</span></span>
    <button title={pinned ? "Turn off always on top" : "Keep always on top"} aria-label="Always on top" aria-pressed={pinned} class:active={pinned} onclick={pin}><span class="material-symbols-outlined">push_pin</span></button>
    <button title="Return to full player" aria-label="Return to full player" onclick={restore}><span class="material-symbols-outlined">open_in_full</span></button>
    <button title="Close mini player" aria-label="Close mini player" onclick={restore}><span class="material-symbols-outlined">close</span></button>
  </div>
  <div class="mini-track">
    <div class="mini-art">{#if art}<img src={art} alt="" referrerpolicy="no-referrer" />{:else}<span class="material-symbols-outlined">music_note</span>{/if}</div>
    <div class="mini-meta"><div class="mini-song" title={player.videoDetails?.title}>{player.videoDetails?.title || "Nothing playing"}</div><div class="mini-artist">{player.videoDetails?.author || "Choose music in the full player"}</div><div class="mini-state" role="status">{error || player.playbackError || (!connected ? "Connecting…" : player.trackState === "Buffering" ? "Buffering…" : playing ? "Playing" : player.videoDetails ? "Paused" : "Ready")}</div></div>
  </div>
  <div class="mini-seek"><span>{time(progress)}</span><input aria-label="Seek" type="range" min="0" max={duration || 1} step="1" value={progress} disabled={!duration} oninput={(e) => { scrubbing = true; scrub = Number(e.currentTarget.value); }} onchange={(e) => { void command(`seek:${e.currentTarget.value}`); scrubbing = false; }} /><span>{time(duration)}</span></div>
  <div class="mini-controls">
    <button aria-label="Previous track" title="Previous track" disabled={!player.videoDetails} onclick={() => command("previous")}><span class="material-symbols-outlined">skip_previous</span></button>
    <button class="mini-play" aria-label={playing ? "Pause" : "Play"} title={playing ? "Pause" : "Play"} disabled={!player.videoDetails} onclick={() => command("playPause")}><span class="material-symbols-outlined">{playing ? "pause" : "play_arrow"}</span></button>
    <button aria-label="Next track" title="Next track" disabled={!player.videoDetails} onclick={() => command("next")}><span class="material-symbols-outlined">skip_next</span></button>
    <span class="volume-icon material-symbols-outlined" aria-hidden="true">volume_up</span><input aria-label="Volume" type="range" min="0" max="100" value={player.volume ?? 100} oninput={(e) => command(`volume:${e.currentTarget.value}`)} />
  </div>
</main>

<style>
  .mini-player { height: 100%; width: 100%; border: 1px solid var(--md-sys-color-outline); background: var(--md-sys-color-surface); display: flex; flex-direction: column; }
  .mini-titlebar { display: flex; align-items: center; height: 30px; flex-shrink: 0; padding: 0 4px 0 12px; background: var(--md-sys-color-surface-container-lowest); border-bottom: var(--hairline); cursor: grab; }
  .mini-brand { flex: 1; color: var(--md-sys-color-primary); font-size: 11px; }
  .mini-brand > span { color: var(--md-sys-color-on-surface-variant); }
  button { display: grid; place-items: center; padding: 0; width: 30px; height: 28px; }
  button:hover { background: var(--md-sys-color-surface-container-high); }
  button.active { color: var(--md-sys-color-primary); }
  .material-symbols-outlined { font-size: 19px; }
  .mini-titlebar .material-symbols-outlined { font-size: 16px; }
  .mini-track { display: flex; gap: 12px; align-items: center; padding: 12px 14px 6px; min-height: 74px; }
  .mini-art { width: 52px; height: 52px; flex: 0 0 52px; display: grid; place-items: center; background: var(--md-sys-color-surface-container); border: var(--hairline); }
  img { width: 100%; height: 100%; object-fit: cover; }
  .mini-meta { min-width: 0; }
  .mini-song { font-size: 13px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .mini-artist { color: var(--md-sys-color-on-surface-variant); font-size: 11px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .mini-state { color: var(--md-sys-color-primary); font-size: 10px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; margin-top: 4px; }
  .mini-seek { display: flex; align-items: center; gap: 9px; padding: 2px 14px; font-size: 10px; color: var(--md-sys-color-on-surface-variant); }
  input[type=range] { accent-color: var(--md-sys-color-primary); min-width: 0; height: 16px; }
  .mini-seek input { flex: 1; }
  .mini-controls { display: flex; align-items: center; gap: 6px; padding: 4px 14px 8px; }
  .mini-controls button { height: 30px; width: 34px; }
  .mini-play { color: var(--md-sys-color-on-primary); background: var(--md-sys-color-primary); }
  .mini-play:hover { filter: brightness(1.1); background: var(--md-sys-color-primary); }
  .volume-icon { margin-left: auto; color: var(--md-sys-color-on-surface-variant); }
  .mini-controls input { width: 100px; }
</style>
