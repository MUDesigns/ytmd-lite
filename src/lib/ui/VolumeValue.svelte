<script lang="ts">
  let { value, onchange, percent = false }: {
    value: number;
    onchange: (value: number) => void | Promise<void>;
    percent?: boolean;
  } = $props();

  let editing = $state(false);
  let draft = $state("");

  function commit() {
    if (!editing) return;
    editing = false;
    const next = Number(draft);
    if (draft.trim() && Number.isFinite(next)) {
      void onchange(Math.max(0, Math.min(100, Math.round(next))));
    }
  }
</script>

<span class="volume-value">
  <input
    type="text"
    inputmode="numeric"
    aria-label="Volume percentage"
    title="Type a volume from 0 to 100. Enter to apply, Escape to cancel."
    value={editing ? draft : Math.round(value)}
    onfocus={(e) => { draft = String(Math.round(value)); editing = true; e.currentTarget.select(); }}
    oninput={(e) => { draft = e.currentTarget.value; }}
    onblur={commit}
    onkeydown={(e) => {
      e.stopPropagation();
      if (e.key === "Enter") {
        e.preventDefault();
        e.currentTarget.blur();
      } else if (e.key === "Escape") {
        e.preventDefault();
        editing = false;
        e.currentTarget.blur();
      }
    }}
  />
  {#if percent}<span aria-hidden="true">%</span>{/if}
</span>

<style>
  .volume-value { display: inline-flex; align-items: center; flex-shrink: 0; color: inherit; font-size: 10px; font-variant-numeric: tabular-nums; }
  input { box-sizing: content-box; width: 3ch; min-width: 0; padding: 4px 3px; border: 1px solid transparent; border-radius: 3px; background: transparent; color: inherit; font: inherit; text-align: right; }
  input:hover { border-color: var(--md-sys-color-outline); }
  input:focus { outline: 2px solid var(--md-sys-color-primary); outline-offset: 1px; background: var(--md-sys-color-surface-container-lowest); color: var(--md-sys-color-on-surface); }
</style>
