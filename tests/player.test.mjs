import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import ts from "typescript";

const source = (await readFile(new URL("../src/lib/player.ts", import.meta.url), "utf8"))
  .replace('import { invoke } from "@tauri-apps/api/core";', 'const invoke = async () => {};')
  .replace('import { API_BASE, streamUrl } from "./api";',
    'const API_BASE = "http://localhost"; const streamUrl = id => `/audio-stream/${id}`;');
const { outputText } = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
});
let instance = 0;
const track = (videoId) => ({ videoId, title: videoId });
const flush = () => new Promise((resolve) => setImmediate(resolve));

async function setup(t, fetchImpl = async () => Response.json({ ok: true })) {
  const audios = [];
  class FakeAudio extends EventTarget {
    src = "";
    paused = true;
    readyState = 0;
    currentTime = 0;
    duration = 120;
    autoReady = true;
    constructor() { super(); audios.push(this); }
    pause() { this.paused = true; }
    removeAttribute() { this.src = ""; this.readyState = 0; }
    getAttribute() { return this.src; }
    load() {
      if (this.src && this.autoReady) queueMicrotask(() => {
        this.readyState = 3;
        this.dispatchEvent(new Event("canplay"));
      });
    }
    async setSinkId() {}
    async play() {
      this.paused = false;
      this.dispatchEvent(new Event("playing"));
    }
  }
  globalThis.Audio = FakeAudio;
  globalThis.HTMLMediaElement = { HAVE_FUTURE_DATA: 3 };
  t.mock.method(globalThis, "setInterval", () => 1);
  t.mock.method(globalThis, "clearInterval", () => {});
  const fetchMock = t.mock.method(globalThis, "fetch", fetchImpl);
  const player = await import(`data:text/javascript;base64,${Buffer.from(outputText + `\n// ${instance++}`).toString("base64")}`);
  t.after(() => player.clearQueue());
  return { player, audios, fetchMock };
}

test("playing prepares only the next song, and queue edits replace preparation", async (t) => {
  const { player, fetchMock } = await setup(t);
  await player.playItems([track("a"), track("b"), track("c")]);
  await flush();
  assert.deepEqual(fetchMock.mock.calls.map(c => new URL(c.arguments[0]).pathname),
    ["/audio-stream/a/warm", "/audio-stream/b/warm"]);
  player.playNext(track("inserted"));
  await flush();
  assert.equal(new URL(fetchMock.mock.calls.at(-1).arguments[0]).pathname, "/audio-stream/inserted/warm");
});

test("a late old resolution cannot overwrite a newer selection", async (t) => {
  let finishOld;
  const { player, audios } = await setup(t, (url) => url.includes("/old/")
    ? new Promise(resolve => { finishOld = resolve; })
    : Promise.resolve(Response.json({ ok: true })));
  const old = player.playItem(track("old"));
  await player.playItem(track("new"));
  finishOld(Response.json({ ok: true }));
  await old;
  assert.equal(audios[0].src, "/audio-stream/new");
  assert.equal(player.getSnapshot().trackState, "Playing");
});

test("clearing the queue cancels resolution without later autoplay", async (t) => {
  let finish;
  const { player, audios } = await setup(t, () => new Promise(resolve => { finish = resolve; }));
  const pending = player.playItem(track("a"));
  player.clearQueue();
  finish(Response.json({ ok: true }));
  await pending;
  assert.equal(audios[0].src, "");
  assert.equal(audios[0].paused, true);
  assert.equal(player.getSnapshot().queue.length, 0);
});

test("an extraction error is not retried through another endpoint", async (t) => {
  const { player, fetchMock } = await setup(t, async () => Response.json({ ok: false, error: "offline" }, { status: 503 }));
  await assert.rejects(player.playItem(track("a")), /offline/);
  assert.equal(fetchMock.mock.callCount(), 1);
  assert.equal(player.getSnapshot().trackState, "Paused");
});

test("pause during resolution prevents autoplay and can resume the loaded track", async (t) => {
  let finish;
  const { player, audios } = await setup(t, () => new Promise(resolve => { finish = resolve; }));
  const pending = player.playItem(track("a"));
  await player.playPause();
  finish(Response.json({ ok: true }));
  await pending;
  assert.equal(audios[0].paused, true);
  await player.playPause();
  assert.equal(audios[0].paused, false);
});

test("shuffle preparation matches the actual next selection", async (t) => {
  const { player, fetchMock } = await setup(t);
  await player.playItemsShuffled([track("a"), track("b"), track("c")]);
  await flush();
  const prepared = fetchMock.mock.calls[1].arguments[0];
  await player.next();
  assert.equal(fetchMock.mock.calls[2].arguments[0], prepared);
});

test("switching songs cancels a pending canplay wait without pausing the new song", async (t) => {
  const { player, audios } = await setup(t);
  const old = player.playItem(track("old"));
  audios[0].autoReady = false;
  await flush();
  assert.equal(player.getSnapshot().trackState, "Buffering");
  audios[0].autoReady = true;
  await player.playItem(track("new"));
  await old;
  assert.equal(audios[0].src, "/audio-stream/new");
  assert.equal(audios[0].paused, false);
});

test("preparation failures do not stop current playback", async (t) => {
  const { player } = await setup(t, async (url) => url.includes("/next/")
    ? Response.json({ error: "offline" }, { status: 503 })
    : Response.json({ ok: true }));
  await player.playItems([track("current"), track("next")]);
  await flush();
  assert.equal(player.getSnapshot().trackState, "Playing");
});
