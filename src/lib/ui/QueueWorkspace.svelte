<script lang="ts">
  import type { PlayerState } from "$lib/types";
  import { albumKey, arrangeQueue, emptyRules, readHeard, type ListeningSession } from "$lib/listening";
  import * as playerCtl from "$lib/player";
  let { player, sessions, onsave, onresume, onrename, ondelete, onstatus }: {
    player: PlayerState; sessions: ListeningSession[];
    onsave: (name: string, replaceId?: string) => void;
    onresume: (session: ListeningSession) => void;
    onrename: (id: string, name: string) => void;
    ondelete: (id: string) => void;
    onstatus: (message: string) => void;
  } = $props();
  let name = $state("");
  let rules = $state({ ...emptyRules });
  let previewOpen = $state(false);
  let deleteId = $state("");
  const index = $derived(player.queueIndex ?? -1);
  const current = $derived(player.queue?.[index]);
  const upcoming = $derived((player.queue || []).slice(index + 1));
  const preview = $derived(previewOpen ? arrangeQueue(player.queue || [], index, rules, readHeard()).slice(index + 1) : []);
  const hasRules = $derived(rules.noRepeatArtists || rules.unplayedOnly || rules.keepAlbums);
  function apply() {
    playerCtl.applyQueueRules(rules);
    previewOpen = false;
    onstatus("Queue rules applied to upcoming tracks. Shuffle is off; new additions can be previewed again.");
  }
  function stopAlbum(enabled: boolean) {
    try { playerCtl.setStopAfterAlbum(enabled); }
    catch (e) { onstatus(String(e)); }
  }
  function time(seconds: number) { return `${Math.floor(seconds / 60)}:${String(Math.floor(seconds % 60)).padStart(2, "0")}`; }
</script>

<div class="queue-workspace">
  <div class="workspace-section">
    <label class="autoplay-toggle"><input type="checkbox" checked={!!player.autoplay}
      onchange={(e) => playerCtl.setAutoplay(e.currentTarget.checked)} /> Autoplay similar songs</label>
    <p>Continue with YouTube Music recommendations after your queue. Songs you add play first. Turning this off removes upcoming autoplay suggestions.</p>
    {#if player.autoplayLoading}<p role="status">Finding similar songs…</p>{/if}
    {#if player.autoplayError}<p role="status">{player.autoplayError}</p>{/if}
  </div>
  <details class="workspace-section" open>
    <summary>Saved sessions <span>{sessions.length} / 30</span></summary>
    <p>Save your queue and position on this device. Resume replaces the current queue and starts playback.</p>
    <form class="workspace-actions" onsubmit={(e) => { e.preventDefault(); onsave(name); }}>
      <input aria-label="New session name" placeholder="e.g. Late-night coding" maxlength="80" bind:value={name} />
      <button type="submit" disabled={!name.trim() || !player.queue?.length}>Save session</button>
    </form>
    {#each sessions as session (session.id)}
      <div class="session-entry">
        <div class="session-info">
          <input aria-label={`Rename session ${session.name}`} value={session.name} maxlength="80" onchange={(e) => onrename(session.id, e.currentTarget.value)} />
          <span>{session.queue.length} tracks · {session.queue[session.index].title} · {time(session.position)}</span>
          <small>Saved {new Date(session.savedAt).toLocaleString()}</small>
        </div>
        <div class="workspace-actions">
          <button onclick={() => onresume(session)}>Resume</button>
          <button disabled={!player.queue?.length} title="Replace this saved session with the current queue and position" onclick={() => onsave(session.name, session.id)}>Update</button>
          {#if deleteId === session.id}
            <button class="danger" onclick={() => { ondelete(session.id); deleteId = ""; }}>Confirm delete</button>
            <button onclick={() => deleteId = ""}>Cancel</button>
          {:else}
            <button onclick={() => deleteId = session.id}>Delete</button>
          {/if}
        </div>
      </div>
    {/each}
    {#if !sessions.length}<p>No saved sessions yet. Start a queue, then give it a name.</p>{/if}
  </details>
  <details class="workspace-section">
    <summary>Queue rules <span>{upcoming.length} upcoming</span></summary>
    <p>Arrange upcoming tracks without interrupting the current song. Apply again after adding more music.</p>
    <div class="rule-options">
      <label><input type="checkbox" bind:checked={rules.noRepeatArtists} onchange={() => previewOpen = false} /> No repeated artists <small>One track per primary artist, including artists already in this queue.</small></label>
      <label><input type="checkbox" bind:checked={rules.unplayedOnly} onchange={() => previewOpen = false} /> Unplayed tracks only <small>Based on tracks heard for 30 seconds or completed in this app, on this device.</small></label>
      <label><input type="checkbox" bind:checked={rules.keepAlbums} onchange={() => previewOpen = false} /> Keep albums together <small>Group known albums in queue order. Filters above take priority.</small></label>
    </div>
    <button disabled={!upcoming.length || !hasRules} onclick={() => previewOpen = !previewOpen}>{previewOpen ? "Hide preview" : "Preview changes"}</button>
    {#if previewOpen}
      <div class="rule-preview">
        <p>{preview.length} upcoming · {upcoming.length - preview.length} removed. Applying turns shuffle off.</p>
        {#if !preview.length}<p>All upcoming tracks will be removed. Your current song will continue.</p>{/if}
        <ol>{#each preview.slice(0, 8) as track}<li>{track.title} <span>— {track.author || "Unknown artist"}</span></li>{/each}</ol>
        {#if preview.length > 8}<p>…and {preview.length - 8} more tracks.</p>{/if}
        <button onclick={apply}>Apply to upcoming queue</button>
      </div>
    {/if}
    <label class="album-stop"><input type="checkbox" checked={!!player.stopAfterAlbum} disabled={!albumKey(current)} onchange={(e) => stopAlbum(e.currentTarget.checked)} /> Stop after this album</label>
    <p>{current?.album || (albumKey(current) ? "Current album" : "Start a track from an album page to use this.")} · Stops at the next album boundary. Enabling turns shuffle and repeat off; changing either cancels the stop.</p>
  </details>
</div>

<style>
  .queue-workspace { display: grid; gap: 12px; margin-bottom: 20px; }
  .autoplay-toggle { cursor: pointer; font-weight: 600; color: var(--md-sys-color-primary); }
  .workspace-section { border: 1px solid var(--md-sys-color-outline); background: var(--md-sys-color-surface-container-low); padding: 12px; }
  summary { cursor: pointer; color: var(--md-sys-color-primary); font-weight: 600; }
  summary span { margin-left: 12px; color: var(--md-sys-color-on-surface-variant); font-size: 10px; font-weight: 400; }
  p, small, .session-info > span, li span { color: var(--md-sys-color-on-surface-variant); font-size: 11px; }
  p { line-height: 1.6; }
  .workspace-actions { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
  input:not([type=checkbox]) { min-width: 120px; flex: 1; padding: 8px; border: 1px solid var(--md-sys-color-outline); background: var(--md-sys-color-surface); color: inherit; font: inherit; }
  button { padding: 7px 10px; border: 1px solid var(--md-sys-color-outline); color: var(--md-sys-color-primary); background: var(--md-sys-color-surface-container); font: inherit; cursor: pointer; }
  button:hover { border-color: var(--md-sys-color-primary); }
  button:disabled { opacity: .45; cursor: default; }
  .session-entry { display: flex; flex-wrap: wrap; gap: 12px; padding-top: 12px; margin-top: 12px; border-top: 1px solid var(--md-sys-color-outline-variant); }
  .session-info { display: grid; gap: 5px; flex: 1; min-width: 180px; }
  .session-info > span { overflow-wrap: anywhere; }
  .rule-options { display: grid; gap: 12px; margin: 16px 0; }
  .rule-options label { cursor: pointer; }
  .rule-options small { display: block; margin: 4px 0 0 24px; }
  input[type=checkbox] { accent-color: var(--md-sys-color-primary); }
  .album-stop { display: block; margin-top: 20px; }
  .rule-preview { border-left: 2px solid var(--md-sys-color-primary); padding-left: 14px; margin-top: 14px; }
  li { padding: 4px 0; overflow-wrap: anywhere; }
  .danger { color: var(--md-sys-color-error); }
  :is(button, input, summary):focus-visible { outline: 2px solid var(--md-sys-color-primary); outline-offset: 2px; }
</style>
