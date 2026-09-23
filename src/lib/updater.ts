import { check } from "@tauri-apps/plugin-updater";
import { relaunch } from "@tauri-apps/plugin-process";
import { invoke } from "@tauri-apps/api/core";

/** Quietly check GitHub Releases for a newer build; install + relaunch when found. */
export async function checkForAppUpdates(): Promise<void> {
  if (import.meta.env.DEV) return;
  let update: Awaited<ReturnType<typeof check>> = null;
  let stoppingBackend = false;
  try {
    update = await check();
    if (!update) return;
    console.info(`[updater] ${update.version} available — downloading…`);
    // Keep playback available during download. Windows install exits directly,
    // bypassing the normal RunEvent::Exit cleanup, so await shutdown first.
    await update.download();
    stoppingBackend = true;
    await invoke("prepare_app_update");
    await update.install();
    await relaunch();
  } catch (e) {
    if (stoppingBackend) {
      console.error("[updater] installation deferred", e);
      await invoke("restore_backend_after_update").catch((error) => {
        console.error("[updater] could not restore playback backend", error);
      });
      return;
    }
    // Offline / unsigned local builds / missing latest.json — ignore
    console.debug("[updater] check skipped:", e);
  } finally {
    await update?.close().catch(() => {});
  }
}
