<script lang="ts">
  import type { MusicItem } from "$lib/types";
  import MusicCard from "./MusicCard.svelte";

  let {
    shelf,
    onopen,
    onplay,
    oncontext,
  }: {
    shelf: MusicItem;
    onopen?: (item: MusicItem) => void;
    onplay?: (item: MusicItem) => void;
    oncontext?: (item: MusicItem, ev: MouseEvent) => void;
  } = $props();

  const items = $derived(shelf.items ?? []);
</script>

{#if items.length}
  <section class="shelf">
    <h2>{shelf.title}</h2>
    <div class="row">
      {#each items as item (item.id + item.title)}
        <MusicCard {item} {onopen} {onplay} {oncontext} />
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
