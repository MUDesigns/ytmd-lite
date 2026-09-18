<script lang="ts">
  import type { ArtistLink } from "$lib/types";

  let {
    artists = [],
    fallback = "",
    onopen,
  }: {
    artists?: ArtistLink[];
    fallback?: string;
    onopen?: (artist: ArtistLink) => void;
  } = $props();

  const links: ArtistLink[] = $derived(
    artists?.length
      ? artists
      : fallback
        ? fallback.split(",").map((n) => ({ name: n.trim() })).filter((a) => a.name)
        : [],
  );

  function click(e: MouseEvent, artist: ArtistLink) {
    if (!onopen) return;
    e.preventDefault();
    e.stopPropagation();
    onopen(artist);
  }
</script>

{#if links.length}
  <span class="artists">
    {#each links as artist, i (artist.name + (artist.browseId || "") + i)}
      {#if i > 0}<span class="sep">, </span>{/if}
      {#if onopen}
        <button
          type="button"
          class="link"
          title={`Go to ${artist.name}`}
          onclick={(e) => click(e, artist)}
        >
          {artist.name}
        </button>
      {:else}
        <span>{artist.name}</span>
      {/if}
    {/each}
  </span>
{/if}

<style>
  .artists {
    display: inline;
  }
  .sep {
    color: inherit;
  }
  .link {
    display: inline;
    padding: 0;
    margin: 0;
    border: 0;
    background: none;
    color: inherit;
    font: inherit;
    cursor: pointer;
    text-decoration: none;
  }
  .link:hover {
    color: var(--md-sys-color-primary);
    text-decoration: underline;
    text-underline-offset: 2px;
  }
</style>
