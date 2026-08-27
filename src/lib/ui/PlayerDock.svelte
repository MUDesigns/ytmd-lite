<script lang="ts">
  import type { PlayerState } from "$lib/types";
  import { thumb } from "$lib/api";
  import * as playerCtl from "$lib/player";

  let {
    player,
    onqueue,
    onlike,
  }: {
    player: PlayerState;
    onqueue?: () => void;
    onlike?: () => void;
  } = $props();

  const playing = $derived(player.trackState === "Playing");
  const buffering = $derived(player.trackState === "Buffering");
  const title = $derived(player.videoDetails?.title ?? "idle");
  const artist = $derived(player.videoDetails?.author || "—");
  const duration = $derived(player.videoDetails?.durationSeconds ?? 0);
  const progress = $derived(player.videoProgress ?? 0);
  const progressPct = $derived(duration > 0 ? Math.min(100, (progress / duration) * 100) : 0);
  const art = $derived(thumb(player.videoDetails));
  const volume = $derived(player.volume ?? 100);
  const stateTag = $derived(
    buffering ? "buf" : playing ? "run" : player.videoDetails ? "stop" : "nil",
  );
  const cmd = $derived(buffering ? "buffer" : playing ? "play" : "pause");

  let scrubbing = $state(false);
  let scrubPct = $state(0);
  const shownPct = $derived(scrubbing ? scrubPct : progressPct);

  function fmtTime(sec: number) {
    const s = Math.max(0, Math.floor(sec));
    const m = Math.floor(s / 60);
    const r = s % 60;
    return `${m}:${r.toString().padStart(2, "0")}`;
  }

  function onSeekInput(ev: Event) {
    const el = ev.currentTarget as HTMLInputElement;
    scrubbing = true;
    scrubPct = Number(el.value);
  }

  async function onSeekCommit(ev: Event) {
    const el = ev.currentTarget as HTMLInputElement;
    const pct = Number(el.value);
    scrubbing = false;
    if (!duration) return;
    await playerCtl.seek((pct / 100) * duration);
  }

  async function onVolume(ev: Event) {
    const el = ev.currentTarget as HTMLInputElement;
    await playerCtl.setVolume(Number(el.value));
  }
</script>

<footer class="term" class:buffering>
  <div class="term-chrome">
    <div class="tabs">
      <span class="tab active">PLAYBACK</span>
    </div>
    <div class="chrome-meta">
      <span class="pill" data-state={stateTag}>{stateTag}</span>
      {#if buffering}
        <span class="buf-msg">resolving stream…</span>
      {:else}
        <span class="path">ytmd://playback</span>
      {/if}
    </div>
    <button class="term-btn ghost" title="Queue" onclick={() => onqueue?.()}>
      <svg viewBox="0 0 24 24" aria-hidden="true"
        ><path
          fill="currentColor"
          d="M15 6H3v2h12V6zm0 4H3v2h12v-2zM3 16h8v-2H3v2zM17 6v8.18c-.31-.11-.65-.18-1-.18-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3V8h3V6h-5z"
        /></svg
      >
    </button>
  </div>

  {#if buffering}
    <div class="indeterminate" aria-hidden="true"><div class="indeterminate-bar"></div></div>
  {/if}

  <div class="term-body">
    <div class="side left">
      <div class="art">
        {#if art}
          <img src={art} alt="" referrerpolicy="no-referrer" class:dim={buffering} />
        {:else}
          <svg class="art-fallback" viewBox="0 0 24 24" aria-hidden="true"
            ><path
              fill="currentColor"
              d="M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z"
            /></svg
          >
        {/if}
        {#if buffering}
          <span class="spinner" aria-label="Buffering"></span>
        {/if}
      </div>
      <div class="prompt">
        <div class="line">
          <span class="ps1">ytmd</span>
          <span class="sep">›</span>
          <span class="cmd" class:buf={buffering}>{cmd}</span>
          <span class="arg">"{title}"</span>
        </div>
        <div class="line dim">
          {#if buffering}
            <span class="buf-hint"># waiting for audio bytes…</span>
          {:else}
            <span class="flag">--artist</span>
            <span class="val">{artist}</span>
          {/if}
        </div>
      </div>
    </div>

  <div class="center">
      <div class="btns">
        <button
          class="term-btn"
          class:on={player.shuffle}
          title={player.shuffle ? "Shuffle on" : "Shuffle off"}
          onclick={() => playerCtl.toggleShuffle()}
        >
          <svg viewBox="0 0 24 24" aria-hidden="true"
            ><path
              fill="currentColor"
              d="M10.59 9.17 5.41 4 4 5.41l5.17 5.17 1.42-1.41zM14.5 4l2.04 2.04L4 18.59 5.41 20 17.96 7.46 20 9.5V4h-5.5zm.33 9.41-1.41 1.41 3.13 3.13L14.5 20H20v-5.5l-2.04 2.04-3.13-3.13z"
            /></svg
          >
        </button>
        <button class="term-btn" title="Previous" onclick={() => playerCtl.previous()} disabled={buffering}>
          <svg viewBox="0 0 24 24" aria-hidden="true"
            ><path fill="currentColor" d="M6 6h2v12H6V6zm3.5 6 8.5 6V6l-8.5 6z" /></svg
          >
        </button>
        <button
          class="term-btn primary"
          class:busy={buffering}
          title={buffering ? "Buffering…" : playing ? "Pause" : "Play"}
          onclick={() => playerCtl.playPause()}
          disabled={buffering}
        >
          {#if buffering}
            <span class="spinner sm"></span>
          {:else if playing}
            <svg viewBox="0 0 24 24" aria-hidden="true"
              ><path fill="currentColor" d="M6 5h4v14H6V5zm8 0h4v14h-4V5z" /></svg
            >
          {:else}
            <svg class="ico-play" viewBox="0 0 24 24" aria-hidden="true"
              ><path fill="currentColor" d="M8 5v14l11-7L8 5z" /></svg
            >
          {/if}
        </button>
        <button class="term-btn" title="Next" onclick={() => playerCtl.next()} disabled={buffering}>
          <svg viewBox="0 0 24 24" aria-hidden="true"
            ><path fill="currentColor" d="M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z" /></svg
          >
        </button>
        <button
          class="term-btn"
          class:on={player.repeat && player.repeat !== "off"}
          title={player.repeat === "one" ? "Repeat one" : player.repeat === "all" ? "Repeat all" : "Repeat off"}
          onclick={() => playerCtl.toggleRepeat()}
        >
          {#if player.repeat === "one"}
            <svg viewBox="0 0 24 24" aria-hidden="true"
              ><path
                fill="currentColor"
                d="M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4zm-4-2V9h-1l-2 1v1h1.5v4H13z"
              /></svg
            >
          {:else}
            <svg viewBox="0 0 24 24" aria-hidden="true"
              ><path
                fill="currentColor"
                d="M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4z"
              /></svg
            >
          {/if}
        </button>
      </div>
      <div class="seek" class:busy={buffering}>
        <span class="t">{fmtTime(scrubbing ? (shownPct / 100) * duration : progress)}</span>
        <input
          class="bar"
          type="range"
          min="0"
          max="100"
          step="0.1"
          value={shownPct}
          disabled={buffering}
          oninput={onSeekInput}
          onchange={onSeekCommit}
        />
        <span class="t">{fmtTime(duration)}</span>
      </div>
    </div>

    <div class="side right">
      <button
        class="term-btn ghost"
        class:on={player.likeStatus === "LIKE"}
        title={player.likeStatus === "LIKE" ? "Unlike" : "Like"}
        disabled={!player.videoDetails}
        onclick={() => onlike?.()}
      >
        <svg viewBox="0 0 24 24" aria-hidden="true"
          ><path
            fill="currentColor"
            d={player.likeStatus === "LIKE"
              ? "M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"
              : "M16.5 3c-1.74 0-3.41.81-4.5 2.09C10.91 3.81 9.24 3 7.5 3 4.42 3 2 5.42 2 8.5c0 3.78 3.4 6.86 8.55 11.54L12 21.35l1.45-1.32C18.6 15.36 22 12.28 22 8.5 22 5.42 19.58 3 16.5 3zm-4.4 15.55-.1.1-.1-.1C7.14 14.24 4 11.39 4 8.5 4 6.5 5.5 5 7.5 5c1.54 0 3.04.99 3.57 2.36h1.87C13.46 5.99 14.96 5 16.5 5c2 0 3.5 1.5 3.5 3.5 0 2.89-3.14 5.74-7.9 10.05z"}
          /></svg
        >
      </button>
      <span class="label">vol</span>
      <input class="bar vol" type="range" min="0" max="100" value={volume} oninput={onVolume} />
      <span class="t vol-n">{volume}</span>
    </div>
  </div>
</footer>

<style>
  .term {
    grid-area: player;
    height: var(--player-h);
    display: flex;
    flex-direction: column;
    min-width: 0;
    background: var(--term-bg);
    border-top: 1px solid var(--term-border);
    color: var(--term-fg);
  }

  .term-chrome {
    display: flex;
    align-items: center;
    height: 26px;
    flex-shrink: 0;
    background: var(--term-chrome);
    border-bottom: 1px solid var(--term-border);
    padding-right: 4px;
  }
  .tabs {
    display: flex;
    height: 100%;
  }
  .tab {
    display: inline-flex;
    align-items: center;
    padding: 0 12px;
    font-size: 11px;
    letter-spacing: 0.05em;
    color: var(--term-muted);
    border-right: 1px solid var(--term-border);
  }
  .tab.active {
    color: var(--term-fg);
    background: var(--term-bg);
  }
  .chrome-meta {
    display: flex;
    align-items: center;
    gap: 10px;
    min-width: 0;
    flex: 1;
    padding: 0 10px;
    font-size: 11px;
    color: var(--term-muted);
  }
  .path {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .buf-msg {
    color: #e6c07b;
    animation: status-pulse 1s ease-in-out infinite;
  }
  .pill {
    font-size: 10px;
    padding: 1px 6px;
    border: 1px solid var(--term-border);
    text-transform: uppercase;
    letter-spacing: 0.06em;
  }
  .pill[data-state="run"] {
    color: #7fd99a;
    border-color: color-mix(in srgb, #7fd99a 45%, transparent);
  }
  .pill[data-state="buf"] {
    color: #e6c07b;
    border-color: color-mix(in srgb, #e6c07b 45%, transparent);
  }

  .indeterminate {
    height: 2px;
    background: color-mix(in srgb, #e6c07b 15%, transparent);
    overflow: hidden;
  }
  .indeterminate-bar {
    width: 40%;
    height: 100%;
    background: #e6c07b;
    animation: indeterminate 1.1s ease-in-out infinite;
  }

  /* Equal side columns → transport stays centered */
  .term-body {
    flex: 1;
    min-height: 0;
    display: grid;
    grid-template-columns: minmax(0, 1fr) 280px minmax(0, 1fr);
    align-items: center;
    column-gap: 12px;
    padding: 0 14px;
  }

  .side {
    display: flex;
    align-items: center;
    gap: 10px;
    min-width: 0;
  }
  .side.left {
    justify-content: flex-start;
  }
  .side.right {
    justify-content: flex-end;
  }

  .art {
    position: relative;
    width: 40px;
    height: 40px;
    border: 1px solid var(--term-border);
    background: #0a0c10;
    display: grid;
    place-items: center;
    overflow: hidden;
    flex-shrink: 0;
  }
  .art img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
  .art img.dim {
    opacity: 0.35;
  }
  .art-fallback {
    width: 18px;
    height: 18px;
    color: var(--term-muted);
  }

  .prompt {
    min-width: 0;
  }
  .line {
    display: flex;
    align-items: baseline;
    gap: 6px;
    min-width: 0;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    font-size: 12px;
    line-height: 1.35;
  }
  .line.dim {
    color: var(--term-muted);
    font-size: 11px;
  }
  .ps1 {
    color: var(--term-accent);
    font-weight: 600;
  }
  .sep {
    color: var(--term-muted);
  }
  .cmd {
    color: #7fd99a;
  }
  .cmd.buf {
    color: #e6c07b;
  }
  .arg {
    color: var(--term-fg);
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .flag {
    color: #61afef;
  }
  .val {
    color: #e5c07b;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .buf-hint {
    color: #e6c07b;
  }

  .center {
    width: 280px;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 4px;
    justify-self: center;
  }
  .btns {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 4px;
    height: 28px;
  }

  .term-btn {
    box-sizing: border-box;
    width: 28px;
    height: 28px;
    padding: 0;
    margin: 0;
    border: 1px solid transparent;
    border-radius: 2px;
    color: var(--term-fg);
    background: transparent;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    line-height: 0;
    flex-shrink: 0;
    transform: none !important; /* beat global button:active scale */
  }
  .term-btn svg {
    width: 16px;
    height: 16px;
    display: block;
  }
  .term-btn .ico-play {
    /* triangle sits left in 24 viewBox — nudge for optical center */
    transform: translateX(1px);
  }
  .term-btn.ghost {
    width: 28px;
    height: 26px;
    color: var(--term-muted);
  }
  .term-btn.on {
    border-color: color-mix(in srgb, var(--term-accent) 50%, transparent);
    background: color-mix(in srgb, var(--term-accent) 16%, transparent);
    color: var(--term-accent);
  }
  .term-btn:hover:not(:disabled) {
    background: color-mix(in srgb, var(--term-fg) 8%, transparent);
    border-color: var(--term-border);
  }
  .term-btn.primary {
    border-color: color-mix(in srgb, var(--term-accent) 50%, transparent);
    background: color-mix(in srgb, var(--term-accent) 14%, transparent);
    color: var(--term-accent);
  }
  .term-btn.primary:hover:not(:disabled) {
    background: color-mix(in srgb, var(--term-accent) 24%, transparent);
  }
  .term-btn.busy {
    border-color: color-mix(in srgb, #e6c07b 45%, transparent);
    color: #e6c07b;
  }
  .term-btn:disabled {
    opacity: 0.45;
  }

  .seek {
    display: flex;
    align-items: center;
    gap: 8px;
    width: 100%;
  }
  .seek.busy {
    opacity: 0.55;
  }

  .bar {
    flex: 1;
    min-width: 0;
    height: 14px;
    margin: 0;
    -webkit-appearance: none;
    appearance: none;
    background: transparent;
    cursor: pointer;
  }
  .bar::-webkit-slider-runnable-track {
    height: 3px;
    background: var(--term-border);
  }
  .bar::-webkit-slider-thumb {
    -webkit-appearance: none;
    width: 8px;
    height: 12px;
    margin-top: -4.5px;
    background: var(--term-accent);
    border: none;
    border-radius: 1px;
  }
  .bar::-moz-range-track {
    height: 3px;
    background: var(--term-border);
  }
  .bar::-moz-range-thumb {
    width: 8px;
    height: 12px;
    background: var(--term-accent);
    border: none;
    border-radius: 1px;
  }

  .t,
  .label {
    font-size: 10px;
    font-variant-numeric: tabular-nums;
    color: var(--term-muted);
  }
  .t {
    min-width: 28px;
    text-align: center;
  }
  .vol-n {
    min-width: 22px;
    text-align: right;
  }
  .label {
    letter-spacing: 0.04em;
  }
  .vol {
    width: 88px;
    flex: 0 0 88px;
  }

  .spinner {
    position: absolute;
    inset: 0;
    margin: auto;
    width: 16px;
    height: 16px;
    border-radius: 999px;
    border: 2px solid color-mix(in srgb, #e6c07b 35%, transparent);
    border-top-color: #e6c07b;
    animation: spin 0.7s linear infinite;
  }
  .spinner.sm {
    position: static;
    width: 12px;
    height: 12px;
  }

  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }
  @keyframes indeterminate {
    0% {
      transform: translateX(-120%);
    }
    100% {
      transform: translateX(320%);
    }
  }

  @media (max-width: 860px) {
    .term-body {
      grid-template-columns: minmax(0, 1fr) 220px minmax(0, 1fr);
      column-gap: 8px;
      padding: 0 10px;
    }
    .center {
      width: 220px;
    }
    .line.dim {
      display: none;
    }
    .side.right .label {
      display: none;
    }
    .vol {
      width: 64px;
      flex-basis: 64px;
    }
  }
</style>
