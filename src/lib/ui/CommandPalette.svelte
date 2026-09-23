<script lang="ts">
  import { tick } from "svelte";
  import { search } from "$lib/api";
  import type { MusicItem } from "$lib/types";
  import type { PaletteCommand } from "$lib/commands";
  let { open, commands, onclose, onitem, onerror }: {
    open: boolean; commands: PaletteCommand[]; onclose: () => void;
    onitem: (item: MusicItem, action: "play" | "queue" | "open") => unknown | Promise<unknown>;
    onerror: (message: string) => void;
  } = $props();
  let dialog: HTMLDialogElement;
  let input: HTMLInputElement;
  let query = $state("");
  let found = $state<MusicItem[]>([]);
  let selected = $state(0);
  let busy = $state(false);
  let error = $state("");
  const action = $derived((query.match(/^(play|queue|open)\s/i)?.[1].toLowerCase() || "open") as "play" | "queue" | "open");
  const term = $derived(query.replace(/^(play|queue|open)\s+/i, "").trim());
  const options = $derived<PaletteCommand[]>([
    ...commands.filter(c => `${c.label} ${c.detail || ""}`.toLowerCase().includes(query.trim().toLowerCase())).slice(0, query ? 10 : 18),
    ...found.map((item, i) => ({ id: `music-${i}`, label: `${action === "play" ? "Play" : action === "queue" ? "Queue" : "Open"} · ${item.title}`, detail: `${item.type} · ${item.subtitle || "Music"}`, run: () => onitem(item, action) })),
  ]);
  $effect(() => {
    if (open && dialog) {
      query = ""; selected = 0; found = []; error = "";
      if (!dialog.open) dialog.showModal();
      void tick().then(() => input?.focus());
    } else if (dialog?.open) dialog.close();
  });
  $effect(() => {
    const value = term;
    const visible = open;
    const mode = action;
    let cancelled = false;
    found = []; error = ""; selected = 0; busy = visible && value.length >= 2;
    if (!visible || value.length < 2) return;
    const timer = setTimeout(async () => {
      try {
        const result = await search(value, "all");
        if (!cancelled) found = result.items.filter(t => mode === "open" || (t.type !== "artist" && t.type !== "mood")).slice(0, 20);
      } catch (e) { if (!cancelled) error = `Music search unavailable: ${e instanceof Error ? e.message : String(e)}`; }
      finally { if (!cancelled) busy = false; }
    }, 300);
    return () => { cancelled = true; clearTimeout(timer); };
  });
  async function choose(option?: PaletteCommand) {
    if (!option) return;
    onclose();
    try { await option.run(); } catch (e) { onerror(String(e)); }
  }
  function keydown(e: KeyboardEvent) {
    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      selected = (selected + (e.key === "ArrowDown" ? 1 : -1) + options.length) % Math.max(1, options.length);
      void tick().then(() => document.getElementById(`command-option-${selected}`)?.scrollIntoView({ block: "nearest" }));
    } else if (e.key === "Enter" && e.target === input) {
      e.preventDefault(); void choose(options[Math.min(selected, options.length - 1)]);
    }
  }
</script>

<dialog bind:this={dialog} class="command-palette" onclose={onclose} oncancel={onclose} onkeydown={keydown} aria-labelledby="command-palette-title">
  <div class="palette-header"><h2 id="command-palette-title">Command palette</h2><button title="Close command palette" aria-label="Close command palette" onclick={onclose}>Esc</button></div>
  <input bind:this={input} bind:value={query} role="combobox" aria-label="Search commands or music" aria-expanded="true" aria-controls="command-options" aria-activedescendant={options.length ? `command-option-${Math.min(selected, options.length - 1)}` : undefined} aria-autocomplete="list" placeholder="Search commands, or type play / queue / open…" autocomplete="off" />
  <p class="palette-help">Try “queue Radiohead”, “play an album”, “output”, or “session”.</p>
  <div id="command-options" class="palette-options" role="listbox" aria-label="Commands and music">
    {#each options as option, i (option.id)}
      <button role="option" aria-selected={i === selected} id={`command-option-${i}`} class:chosen={i === selected} onclick={() => choose(option)}>
        <span>{option.label}</span><small>{option.detail || "Command"}</small>
      </button>
    {/each}
  </div>
  {#if busy}<p role="status">Searching music…</p>{/if}
  {#if error}<p role="status" class="palette-error">{error}</p>{/if}
  {#if !options.length && !busy && !error}<p>No matches. Try a song, artist, or command name.</p>{/if}
  <div class="palette-footer">↑ ↓ navigate · Enter select · Esc close <span>Ctrl / ⌘ K</span></div>
</dialog>

<style>
  .command-palette { width: min(640px, calc(100vw - 32px)); max-height: min(650px, calc(100vh - 60px)); padding: 16px; border: 1px solid var(--md-sys-color-outline); background: var(--md-sys-color-surface); color: var(--md-sys-color-on-surface); box-shadow: 0 24px 80px #0008; }
  .command-palette::backdrop { background: #0009; }
  .palette-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
  h2 { margin: 0; font-size: 14px; }
  .palette-header button { border: 1px solid var(--md-sys-color-outline); padding: 4px 8px; color: var(--md-sys-color-on-surface-variant); }
  input { width: 100%; padding: 12px; font: inherit; background: var(--md-sys-color-surface-container-lowest); color: inherit; border: 1px solid var(--md-sys-color-outline); }
  p, .palette-footer { font-size: 11px; color: var(--md-sys-color-on-surface-variant); }
  .palette-options { max-height: 380px; overflow: auto; margin: 12px 0; }
  .palette-options button { display: flex; flex-direction: column; gap: 4px; width: 100%; text-align: left; padding: 10px; border: 1px solid transparent; font: inherit; color: inherit; cursor: pointer; }
  .palette-options button.chosen, .palette-options button:hover { background: var(--md-sys-color-surface-container); border-color: var(--md-sys-color-outline); }
  .palette-options button.chosen > span { color: var(--md-sys-color-primary); }
  small { color: var(--md-sys-color-on-surface-variant); }
  .palette-footer { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 8px; border-top: 1px solid var(--md-sys-color-outline); padding-top: 12px; }
  .palette-error { color: var(--md-sys-color-error); }
  :is(input, button):focus-visible { outline: 2px solid var(--md-sys-color-primary); outline-offset: 1px; }
</style>
