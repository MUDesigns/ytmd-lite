<script lang="ts">
  import { onMount } from "svelte";
  import {
    DEFAULT_ACCENT,
    applyAccent,
    cmykToRgb,
    normalizeAccent,
    parseColorInput,
    rgbToCmyk,
    rgbToHex,
    type Cmyk,
    type Rgb,
  } from "$lib/accent";

  let {
    value = DEFAULT_ACCENT,
    onchange,
  }: {
    value?: string;
    onchange?: (hex: string) => void;
  } = $props();

  let hex = $state(normalizeAccent(value));
  let rgb = $state<Rgb>({ r: 168, g: 199, b: 250 });
  let cmyk = $state<Cmyk>({ c: 33, m: 20, y: 0, k: 2 });
  let hexDraft = $state(hex);
  let rgbDraft = $state("168, 199, 250");
  let cmykDraft = $state("33, 20, 0, 2");
  let error = $state<string | null>(null);
  let skipProp = false;

  function syncFromRgb(next: Rgb, emit = true) {
    rgb = next;
    hex = rgbToHex(next);
    cmyk = rgbToCmyk(next);
    hexDraft = hex;
    rgbDraft = `${next.r}, ${next.g}, ${next.b}`;
    cmykDraft = `${cmyk.c}, ${cmyk.m}, ${cmyk.y}, ${cmyk.k}`;
    error = null;
    applyAccent(next);
    if (emit) {
      skipProp = true;
      onchange?.(hex);
    }
  }

  onMount(() => {
    const parsed = parseColorInput(normalizeAccent(value));
    if (parsed) syncFromRgb(parsed, false);
  });

  $effect(() => {
    const normalized = normalizeAccent(value);
    if (skipProp) {
      skipProp = false;
      return;
    }
    if (normalized !== hex) {
      const parsed = parseColorInput(normalized);
      if (parsed) syncFromRgb(parsed, false);
    }
  });

  function onPicker(ev: Event) {
    const v = (ev.currentTarget as HTMLInputElement).value;
    const parsed = parseColorInput(v);
    if (parsed) syncFromRgb(parsed);
  }

  function commitHex() {
    const parsed = parseColorInput(hexDraft);
    if (!parsed) {
      error = "Invalid hex — use #RGB, #RRGGBB, or RRGGBB";
      hexDraft = hex;
      return;
    }
    syncFromRgb(parsed);
  }

  function commitRgb() {
    const parsed = parseColorInput(rgbDraft);
    if (!parsed) {
      error = "Invalid RGB — use r, g, b (0–255) or rgb(r, g, b)";
      rgbDraft = `${rgb.r}, ${rgb.g}, ${rgb.b}`;
      return;
    }
    syncFromRgb(parsed);
  }

  function commitCmyk() {
    const parts = cmykDraft
      .trim()
      .split(/[\s,;/]+/)
      .filter(Boolean);
    if (parts.length !== 4 || parts.some((p) => Number.isNaN(Number(p.replace("%", ""))))) {
      error = "Invalid CMYK — use c, m, y, k (0–100)";
      cmykDraft = `${cmyk.c}, ${cmyk.m}, ${cmyk.y}, ${cmyk.k}`;
      return;
    }
    const nums = parts.map((p) => Number(p.replace("%", "")));
    syncFromRgb(cmykToRgb({ c: nums[0], m: nums[1], y: nums[2], k: nums[3] }));
  }

  function reset() {
    const parsed = parseColorInput(DEFAULT_ACCENT);
    if (parsed) syncFromRgb(parsed);
  }
</script>

<div class="accent">
  <div class="accent-row">
    <label class="swatch-wrap" title="Pick a color">
      <input class="native-picker" type="color" value={hex} oninput={onPicker} />
      <span class="swatch" style="background:{hex}"></span>
    </label>
    <div class="fields">
      <label class="field">
        <span class="field-label">hex</span>
        <input
          class="field-input"
          value={hexDraft}
          spellcheck="false"
          placeholder="#a8c7fa"
          onchange={commitHex}
          onkeydown={(e) => e.key === "Enter" && commitHex()}
        />
      </label>
      <label class="field">
        <span class="field-label">rgb</span>
        <input
          class="field-input"
          value={rgbDraft}
          spellcheck="false"
          placeholder="168, 199, 250"
          onchange={commitRgb}
          onkeydown={(e) => e.key === "Enter" && commitRgb()}
        />
      </label>
      <label class="field">
        <span class="field-label">cmyk</span>
        <input
          class="field-input"
          value={cmykDraft}
          spellcheck="false"
          placeholder="33, 20, 0, 2"
          onchange={commitCmyk}
          onkeydown={(e) => e.key === "Enter" && commitCmyk()}
        />
      </label>
    </div>
  </div>
  <div class="accent-actions">
    <button type="button" class="btn" onclick={reset}>Reset default</button>
    <span class="preview muted">preview · primary buttons &amp; accents</span>
  </div>
  {#if error}
    <div class="callout error">{error}</div>
  {/if}
</div>

<style>
  .accent {
    display: flex;
    flex-direction: column;
    gap: 10px;
  }
  .accent-row {
    display: flex;
    gap: 12px;
    align-items: stretch;
  }
  .swatch-wrap {
    position: relative;
    width: 56px;
    flex-shrink: 0;
    border-radius: var(--radius);
    border: 1px solid var(--md-sys-color-outline);
    overflow: hidden;
    cursor: pointer;
  }
  .native-picker {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
    opacity: 0;
    cursor: pointer;
    border: none;
    padding: 0;
  }
  .swatch {
    display: block;
    width: 100%;
    height: 100%;
    min-height: 72px;
    pointer-events: none;
  }
  .fields {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
  .field {
    display: grid;
    grid-template-columns: 44px minmax(0, 1fr);
    align-items: center;
    gap: 8px;
  }
  .field-label {
    font-size: 10px;
    color: var(--md-sys-color-on-surface-variant);
    text-transform: lowercase;
  }
  .field-input {
    width: 100%;
    padding: 5px 8px;
    background: var(--md-sys-color-surface-container-lowest);
    border: 1px solid var(--md-sys-color-outline);
    border-radius: var(--radius);
    color: inherit;
    font: inherit;
  }
  .field-input:focus {
    outline: none;
    border-color: var(--md-sys-color-primary);
  }
  .accent-actions {
    display: flex;
    align-items: center;
    gap: 10px;
    flex-wrap: wrap;
  }
  .preview {
    font-size: 10px;
  }
  .btn {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 6px 12px;
    border-radius: var(--radius);
    border: 1px solid var(--md-sys-color-outline);
    background: var(--md-sys-color-surface-container);
    color: var(--md-sys-color-on-surface);
    font-size: 11px;
  }
  .btn:hover {
    border-color: var(--md-sys-color-primary);
    color: var(--md-sys-color-primary);
  }
  .muted {
    color: var(--md-sys-color-on-surface-variant);
  }
  .callout {
    padding: 8px 10px;
    border-radius: var(--radius);
    background: var(--md-sys-color-surface-container);
    border: 1px solid var(--md-sys-color-outline-variant);
  }
  .callout.error {
    color: var(--md-sys-color-error);
    border-color: color-mix(in srgb, var(--md-sys-color-error) 35%, transparent);
  }
</style>
