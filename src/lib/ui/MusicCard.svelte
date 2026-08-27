<script lang="ts">
  import type { MusicItem } from "$lib/types";
  import { thumb } from "$lib/api";

  let {
    item,
    onopen,
    onplay,
    oncontext,
  }: {
    item: MusicItem;
    onopen?: (item: MusicItem) => void;
    onplay?: (item: MusicItem) => void;
    oncontext?: (item: MusicItem, ev: MouseEvent) => void;
  } = $props();

  const img = $derived(thumb(item));
  const isMood = $derived(item.type === "mood" || !!item.color);
  const canPlay = $derived(
    !!item.videoId || item.type === "album" || item.type === "playlist" || !!item.playlistId || !!item.browseId,
  );

  function open() {
    onopen?.(item);
  }

  function play(e: Event) {
    e.stopPropagation();
    e.preventDefault();
    onplay?.(item);
  }
</script>

<div
  class="card"
  role="button"
  tabindex="0"
  onkeydown={(e) => {
    if (e.key === "Enter") open();
  }}
  onclick={open}
  oncontextmenu={(e) => {
    e.preventDefault();
    e.stopPropagation();
    oncontext?.(item, e);
  }}
>
  <div
    class="art"
    class:round={item.type === "artist"}
    class:mood={isMood}
    style={item.color
      ? `background: linear-gradient(135deg, ${item.color} 0%, color-mix(in srgb, ${item.color} 55%, #000) 100%)`
      : undefined}
  >
    {#if img && !isMood}
      <img src={img} alt="" loading="lazy" referrerpolicy="no-referrer" />
    {:else if isMood}
      <span class="mood-label">{item.title.slice(0, 1)}</span>
    {:else}
      <span class="material-symbols-outlined placeholder">album</span>
    {/if}
    {#if canPlay && !isMood}
      <button type="button" class="play" title="Play" onclick={play}>
        <span class="material-symbols-outlined">play_arrow</span>
      </button>
    {/if}
  </div>
  <div class="meta">
    <div class="title">{item.title}</div>
    {#if item.subtitle}
      <div class="sub">{item.subtitle}</div>
    {:else}
      <div class="sub">{item.type}</div>
    {/if}
  </div>
</div>

<style>
  .card {
    display: flex;
    flex-direction: column;
    gap: 6px;
    width: 140px;
    flex: 0 0 auto;
    text-align: left;
    padding: 6px;
    border-radius: var(--radius);
    border: 1px solid transparent;
    color: inherit;
    cursor: pointer;
    transition:
      background 0.12s var(--ease-out),
      border-color 0.12s var(--ease-out);
  }
  .card:hover {
    background: var(--md-sys-color-surface-container);
    border-color: var(--md-sys-color-outline);
  }
  .art {
    position: relative;
    width: 128px;
    height: 128px;
    border-radius: var(--radius);
    overflow: hidden;
    background: var(--md-sys-color-surface-container-highest);
    display: grid;
    place-items: center;
  }
  .card:hover .art {
    outline: 1px solid var(--md-sys-color-outline);
  }
  .art.round {
    border-radius: 50%;
  }
  .art img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    transition: filter 0.15s var(--ease-out);
  }
  .card:hover .art img {
    filter: brightness(0.82);
  }
  .mood-label {
    font-size: 36px;
    font-weight: 600;
    color: rgba(255, 255, 255, 0.92);
  }
  .placeholder {
    opacity: 0.35;
    font-size: 36px;
  }
  .play {
    position: absolute;
    right: 6px;
    bottom: 6px;
    width: 32px;
    height: 32px;
    border-radius: var(--radius);
    background: var(--md-sys-color-primary);
    color: var(--md-sys-color-on-primary);
    display: grid;
    place-items: center;
    opacity: 0;
    pointer-events: none;
    transition: opacity 0.12s var(--ease-out);
    z-index: 1;
  }
  .play:hover {
    filter: brightness(1.1);
  }
  .card:hover .play {
    opacity: 1;
    pointer-events: auto;
  }
  .title {
    font-size: 12px;
    font-weight: 500;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .sub {
    font-size: 10px;
    color: var(--md-sys-color-on-surface-variant);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
</style>
