<script lang="ts">
  import { tick } from "svelte";
  export type MenuAction = {
    id: string;
    label: string;
    icon?: string;
    disabled?: boolean;
    danger?: boolean;
    separator?: boolean;
  };

  let {
    open = false,
    x = 0,
    y = 0,
    actions = [],
    onselect,
    onclose,
  }: {
    open?: boolean;
    x?: number;
    y?: number;
    actions?: MenuAction[];
    onselect?: (id: string) => void;
    onclose?: () => void;
  } = $props();

  let menuEl = $state<HTMLDivElement | null>(null);

  $effect(() => {
    if (!open) return;
    const previousFocus = document.activeElement as HTMLElement | null;
    void tick().then(() => {
      if (open) menuEl?.querySelector<HTMLButtonElement>("button:not(:disabled)")?.focus();
    });
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.preventDefault();
        onclose?.();
        previousFocus?.focus();
      } else if (e.key === "Tab") {
        onclose?.();
      } else if (["ArrowDown", "ArrowUp", "Home", "End"].includes(e.key)) {
        const buttons = Array.from(menuEl?.querySelectorAll<HTMLButtonElement>("button:not(:disabled)") ?? []);
        if (!buttons.length) return;
        e.preventDefault();
        const index = buttons.indexOf(document.activeElement as HTMLButtonElement);
        const next = e.key === "Home" ? 0 : e.key === "End" ? buttons.length - 1
          : (index + (e.key === "ArrowDown" ? 1 : -1) + buttons.length) % buttons.length;
        buttons[next]?.focus();
      }
    };
    const onDown = (e: MouseEvent) => {
      if (menuEl && !menuEl.contains(e.target as Node)) onclose?.();
    };
    window.addEventListener("keydown", onKey);
    window.addEventListener("mousedown", onDown, true);
    return () => {
      window.removeEventListener("keydown", onKey);
      window.removeEventListener("mousedown", onDown, true);
    };
  });

  const style = $derived.by(() => {
    const pad = 8;
    const w = 220;
    const h = Math.max(40, actions.filter((a) => !a.separator).length * 32 + actions.filter((a) => a.separator).length * 9);
    const left = Math.min(x, Math.max(pad, window.innerWidth - w - pad));
    const top = Math.min(y, Math.max(pad, window.innerHeight - h - pad));
    return `left:${left}px;top:${top}px`;
  });
</script>

{#if open}
  <div
    class="ctx"
    style={style}
    role="menu"
    tabindex="-1"
    bind:this={menuEl}
    oncontextmenu={(e) => e.preventDefault()}
  >
    {#each actions as action (action.id + action.label)}
      {#if action.separator}
        <div class="sep" role="separator"></div>
      {:else}
        <button
          type="button"
          class="item"
          class:danger={action.danger}
          role="menuitem"
          disabled={action.disabled}
          onclick={() => {
            if (action.disabled) return;
            onselect?.(action.id);
            onclose?.();
          }}
        >
          {#if action.icon}
            <span class="material-symbols-outlined">{action.icon}</span>
          {/if}
          <span>{action.label}</span>
        </button>
      {/if}
    {/each}
  </div>
{/if}

<style>
  .ctx {
    position: fixed;
    z-index: 10000;
    min-width: 200px;
    max-width: 280px;
    padding: 4px;
    background: var(--md-sys-color-surface-container-high);
    border: 1px solid var(--md-sys-color-outline);
    border-radius: var(--radius);
    box-shadow: 0 8px 24px rgba(0, 0, 0, 0.45);
  }
  .item {
    width: 100%;
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 6px 8px;
    border-radius: var(--radius);
    text-align: left;
    color: var(--md-sys-color-on-surface);
    font: inherit;
    font-size: 11px;
  }
  .item:hover:not(:disabled),
  .item:focus-visible {
    background: color-mix(in srgb, var(--md-sys-color-primary) 14%, transparent);
    color: var(--md-sys-color-primary);
  }
  .item:disabled {
    opacity: 0.4;
  }
  .item.danger {
    color: var(--md-sys-color-error);
  }
  .item .material-symbols-outlined {
    font-size: 16px;
  }
  .sep {
    height: 1px;
    margin: 4px 6px;
    background: var(--md-sys-color-outline-variant);
  }
</style>
