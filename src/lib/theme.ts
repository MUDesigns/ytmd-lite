import { writable } from "svelte/store";

const STORAGE_KEY = "ytmd.codeTheme";
export const codeTheme = writable(false);

export function setCodeTheme(enabled: boolean) {
  codeTheme.set(enabled);
  document.documentElement.dataset.uiTheme = enabled ? "code" : "default";
  try {
    localStorage.setItem(STORAGE_KEY, String(enabled));
  } catch {
    // The theme still works for this session when storage is unavailable.
  }
}

export function loadCodeTheme() {
  let enabled = false;
  try {
    enabled = localStorage.getItem(STORAGE_KEY) === "true";
  } catch {
    // Use the default appearance when storage is unavailable.
  }
  setCodeTheme(enabled);
}
