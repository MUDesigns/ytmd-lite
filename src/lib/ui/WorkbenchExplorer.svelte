<script lang="ts">
  import type { MusicItem, Panel, QueueItem } from "$lib/types";
  import type { ListeningSession } from "$lib/listening";
  import { workspaceViews, workbenchFilename, type WorkbenchFiles } from "$lib/workbench";
  let { panel, files, shelves, queue, sessions, onnav, onopen, onresume }: {
    files: WorkbenchFiles;
    panel: Panel; shelves: MusicItem[]; queue: QueueItem[]; sessions: ListeningSession[];
    onnav: (panel: Panel) => unknown; onopen: (item: MusicItem) => unknown;
    onresume: (session: ListeningSession) => unknown;
  } = $props();
</script>

<aside class="workbench-explorer" aria-label="Music Explorer">
  <div class="explorer-title">EXPLORER <span>YTMD LITE</span></div>
  <details open class="explorer-group">
    <summary>MUSIC WORKSPACE</summary>
    <div class="explorer-files">
      {#each workspaceViews as target}
        <button class:active={panel === target} onclick={() => onnav(target)}><span class="file-symbol" aria-hidden="true">{files[target].symbol}</span>{workbenchFilename(files, target)}</button>
      {/each}
    </div>
  </details>
  {#if shelves.length}
    <details open class="explorer-group">
      <summary>COLLECTIONS</summary>
      {#each shelves as shelf (shelf.id + shelf.title)}
        <details class="explorer-folder">
          <summary><span class="material-symbols-outlined" aria-hidden="true">folder</span><span>{shelf.title}</span><small>{shelf.items?.length || 0}</small></summary>
          {#each shelf.items || [] as item, i (item.id + i)}
            <button class="explorer-item" title={item.title} onclick={() => onopen(item)}><span class="material-symbols-outlined" aria-hidden="true">{item.type === "song" ? "audio_file" : "description"}</span><span>{item.title}</span></button>
          {/each}
        </details>
      {/each}
    </details>
  {/if}
  <details class="explorer-group" open>
    <summary>SAVED SESSIONS <small>{sessions.length}</small></summary>
    {#each sessions as session (session.id)}
      <button class="explorer-item" title={`Resume ${session.name} (replaces current queue)`} onclick={() => onresume(session)}><span class="material-symbols-outlined" aria-hidden="true">history</span><span>{session.name}</span></button>
    {:else}
      <button class="explorer-empty" onclick={() => onnav("queue")}>Save a listening session…</button>
    {/each}
  </details>
  <button class="explorer-queue" onclick={() => onnav("queue")}><span class="material-symbols-outlined" aria-hidden="true">queue_music</span> QUEUE <span>{queue.length}</span></button>
</aside>
