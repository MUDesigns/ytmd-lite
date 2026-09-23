<script lang="ts">
  import type { ArtistLink, MusicItem } from "$lib/types";
  import MusicCard from "./MusicCard.svelte";
  import { codeTheme } from "$lib/theme";

  let {
    shelf,
    onopen,
    onplay,
    oncontext,
    onartist,
  }: {
    shelf: MusicItem;
    onopen?: (item: MusicItem) => void;
    onplay?: (item: MusicItem) => void;
    oncontext?: (item: MusicItem, ev: MouseEvent) => void;
    onartist?: (artist: ArtistLink) => void;
  } = $props();

  const items = $derived(shelf.items ?? []);
  let expanded = $state(true);
  const folderId = $props.id();
</script>

{#if items.length}
  <section class="shelf">
    {#if $codeTheme}
      <h2 class="code-folder-heading">
        <button class="code-folder-toggle" aria-expanded={expanded} aria-controls={folderId} onclick={() => expanded = !expanded}>
          <span class="material-symbols-outlined" aria-hidden="true">{expanded ? "expand_more" : "chevron_right"}</span>
          <span class="material-symbols-outlined code-folder-icon" aria-hidden="true">{expanded ? "folder_open" : "folder"}</span>
          <span>{shelf.title}<span class="code-punctuation" aria-hidden="true"> /</span></span>
          <span class="code-item-count">{items.length} items</span>
        </button>
      </h2>
    {:else}
      <h2>{shelf.title}</h2>
    {/if}
    <div class="row" id={folderId} hidden={$codeTheme && !expanded}>
      {#each items as item (item.id + item.title)}
        <MusicCard {item} {onopen} {onplay} {oncontext} {onartist} />
      {/each}
    </div>
  </section>
{/if}

<style>
  .shelf {
    margin-bottom: 22px;
  }
  h2 {
    margin: 0 0 10px;
    font-size: 12px;
    font-weight: 600;
    text-transform: lowercase;
    color: var(--md-sys-color-on-surface-variant);
  }
  h2::before {
    content: "// ";
    opacity: 0.55;
  }
  .row {
    display: flex;
    gap: 2px;
    overflow-x: auto;
    padding-bottom: 4px;
    scrollbar-width: thin;
  }
</style>
