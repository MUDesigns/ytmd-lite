import { check } from "@tauri-apps/plugin-updater";
import { relaunch } from "@tauri-apps/plugin-process";

/** Quietly check GitHub Releases for a newer build; install + relaunch when found. */
export async function checkForAppUpdates(): Promise<void> {
  try {
    const update = await check();
    if (!update) return;
    console.info(`[updater] ${update.version} available — downloading…`);
    await update.downloadAndInstall();
    await relaunch();
  } catch (e) {
    // Offline / unsigned local builds / missing latest.json — ignore
    console.debug("[updater] check skipped:", e);
  }
}
