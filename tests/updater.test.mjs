import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import ts from "typescript";

const source = (await readFile(new URL("../src/lib/updater.ts", import.meta.url), "utf8"))
  .replace('import { check } from "@tauri-apps/plugin-updater";', 'const check = (...args) => globalThis.updateTest.check(...args);')
  .replace('import { relaunch } from "@tauri-apps/plugin-process";', 'const relaunch = () => globalThis.updateTest.relaunch();')
  .replace('import { invoke } from "@tauri-apps/api/core";', 'const invoke = (...args) => globalThis.updateTest.invoke(...args);')
  .replace('import.meta.env.DEV', 'globalThis.updateTest.dev');
const { outputText } = ts.transpileModule('const console = { info() {}, debug() {}, error() {} };\n' + source, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
});
const { checkForAppUpdates } = await import(`data:text/javascript;base64,${Buffer.from(outputText).toString("base64")}`);

function setup(failAt, dev = false) {
  const calls = [];
  const action = (name) => async () => {
    calls.push(name);
    if (name === failAt) throw new Error(name);
  };
  globalThis.updateTest = {
    dev,
    check: async () => ({ version: "new", download: action("download"), install: action("install"), close: action("close") }),
    invoke: async (command) => action(command)(),
    relaunch: action("relaunch"),
  };
  return calls;
}

test("download finishes and backend shutdown completes before installation", async () => {
  const calls = setup();
  let releaseShutdown;
  globalThis.updateTest.invoke = async (command) => {
    calls.push(command);
    await new Promise(resolve => { releaseShutdown = resolve; });
  };
  const pending = checkForAppUpdates();
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(calls, ["download", "prepare_app_update"]);
  releaseShutdown();
  await pending;
  assert.deepEqual(calls, ["download", "prepare_app_update", "install", "relaunch", "close"]);
});

test("failed download leaves playback running", async () => {
  const calls = setup("download");
  await checkForAppUpdates();
  assert.deepEqual(calls, ["download", "close"]);
});

test("failed shutdown prevents installation and restores backend availability", async () => {
  const calls = setup("prepare_app_update");
  await checkForAppUpdates();
  assert.deepEqual(calls, ["download", "prepare_app_update", "restore_backend_after_update", "close"]);
});

test("failed install restores backend and does not relaunch", async () => {
  const calls = setup("install");
  await checkForAppUpdates();
  assert.deepEqual(calls, ["download", "prepare_app_update", "install", "restore_backend_after_update", "close"]);
});

test("development builds do not install updates", async () => {
  const calls = setup(undefined, true);
  globalThis.updateTest.check = async () => { throw new Error("Must not check"); };
  await checkForAppUpdates();
  assert.deepEqual(calls, []);
});
