import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import ts from "typescript";

const listeningSource = await readFile(new URL("../src/lib/listening.ts", import.meta.url), "utf8");
const listeningCode = ts.transpileModule(listeningSource, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText;
const listeningUrl = `data:text/javascript;base64,${Buffer.from(listeningCode).toString("base64")}`;

const source = (await readFile(new URL("../src/lib/player.ts", import.meta.url), "utf8"))
  .replace('from "./listening"', `from "${listeningUrl}"`)
  .replace('import { invoke } from "@tauri-apps/api/core";', 'const invoke = async () => {};')
  .replace('import { API_BASE, streamUrl } from "./api";',
    'const API_BASE = "http://localhost"; const streamUrl = id => `/audio-stream/${id}`;');
const { outputText } = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
});
let instance = 0;
const track = (videoId) => ({ videoId, title: videoId });
const flush = () => new Promise((resolve) => setImmediate(resolve));

test("audio outputs omit anonymous duplicates so palette entries have stable unique IDs", async (t) => {
  const { player } = await setup(t);
  const original = Object.getOwnPropertyDescriptor(navigator, "mediaDevices");
  Object.defineProperty(navigator, "mediaDevices", { configurable: true, value: {
    getUserMedia: async () => ({ getTracks: () => [] }),
    enumerateDevices: async () => [
      { kind: "audiooutput", deviceId: "", label: "" },
      { kind: "audiooutput", deviceId: "speakers", label: "Speakers" },
      { kind: "audiooutput", deviceId: "speakers", label: "Speakers" },
    ],
  } });
  t.after(() => original ? Object.defineProperty(navigator, "mediaDevices", original) : delete navigator.mediaDevices);
  assert.deepEqual(await player.listAudioOutputs(), [{ id: "", label: "System default" }, { id: "speakers", label: "Speakers" }]);
});

test("restoring a session resumes at its exact position and replaces the old queue", async (t) => {
  const { player, audios } = await setup(t);
  await player.playItems([track("old")]);
  await player.restoreSession({ queue: [{ videoId: "saved", title: "Saved", author: "Artist", thumbnails: [], selected: false, albumId: "album" }], index: 0, position: 73.5, volume: 42, shuffle: false, repeat: "off", stopAfterAlbum: "album" });
  assert.equal(audios[0].src, "/audio-stream/saved");
  assert.equal(audios[0].currentTime, 73.5);
  assert.equal(audios[0].volume, .42);
  assert.equal(player.getSnapshot().stopAfterAlbum, "album");
  assert.equal(player.getSnapshot().queue.length, 1);
});

test("applying queue rules preserves the playing source and position", async (t) => {
  const { player, audios } = await setup(t);
  await player.playItems([{ ...track("a1"), subtitle: "A", albumId: "a" }, { ...track("b1"), subtitle: "B", albumId: "b" }, { ...track("a2"), subtitle: "A", albumId: "a" }]);
  await player.seek(25);
  player.applyQueueRules({ noRepeatArtists: false, unplayedOnly: false, keepAlbums: true });
  assert.deepEqual(player.getSnapshot().queue.map(t => t.videoId), ["a1", "a2", "b1"]);
  assert.equal(audios[0].currentTime, 25);
  assert.equal(audios[0].src, "/audio-stream/a1");
  assert.equal(audios[0].paused, false);
});

test("stop after album advances inside the album and pauses before the next one", async (t) => {
  const { player, audios } = await setup(t);
  await player.playItems([{ ...track("a1"), albumId: "a" }, { ...track("a2"), albumId: "a" }, { ...track("b1"), albumId: "b" }]);
  player.toggleRepeat();
  player.setStopAfterAlbum(true);
  assert.equal(player.getSnapshot().repeat, "off");
  audios[0].dispatchEvent(new Event("ended"));
  await flush();
  assert.equal(player.getSnapshot().videoDetails.id, "a2");
  audios[0].dispatchEvent(new Event("ended"));
  await flush();
  assert.equal(player.getSnapshot().videoDetails.id, "a2");
  assert.equal(player.getSnapshot().trackState, "Paused");
  assert.equal(audios[0].paused, true);
  assert.equal(player.getSnapshot().stopAfterAlbum, undefined);
});

async function setup(t, fetchImpl = async () => Response.json({ ok: true })) {
  const audios = [];
  class FakeAudio extends EventTarget {
    src = "";
    paused = true;
    readyState = 0;
    currentTime = 0;
    duration = 120;
    autoReady = true;
    error = null;
    constructor() { super(); audios.push(this); }
    pause() { this.paused = true; }
    removeAttribute() { this.src = ""; this.readyState = 0; }
    getAttribute() { return this.src; }
    load() {
      this.error = null;
      this.currentTime = 0;
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
  globalThis.MediaError = { MEDIA_ERR_NETWORK: 2, MEDIA_ERR_DECODE: 3, MEDIA_ERR_SRC_NOT_SUPPORTED: 4 };
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
  assert.equal(player.getSnapshot().trackState, "Error");
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

test("a network interruption refreshes the stream and resumes at the same position", async (t) => {
  const { player, audios, fetchMock } = await setup(t);
  await player.playItem(track("a"));
  audios[0].currentTime = 73;
  audios[0].error = { code: 2 };
  audios[0].dispatchEvent(new Event("error"));
  await flush();
  assert.equal(audios[0].currentTime, 73);
  assert.equal(player.getSnapshot().trackState, "Playing");
  assert.match(fetchMock.mock.calls.at(-1).arguments[0], /refresh=1/);
  // The same track cannot enter an endless automatic reload loop.
  audios[0].currentTime = 80;
  audios[0].error = { code: 2 };
  audios[0].dispatchEvent(new Event("error"));
  await flush();
  assert.equal(player.getSnapshot().trackState, "Error");
  assert.equal(fetchMock.mock.callCount(), 2);
  await player.playPause();
  assert.equal(audios[0].currentTime, 80);
  assert.equal(player.getSnapshot().trackState, "Playing");
});

test("a track change cancels recovery and starts the new song at zero", async (t) => {
  let finishRecovery;
  const { player, audios } = await setup(t, url => url.includes("refresh=1")
    ? new Promise(resolve => { finishRecovery = resolve; })
    : Promise.resolve(Response.json({ ok: true })));
  await player.playItem(track("a"));
  audios[0].currentTime = 73;
  audios[0].error = { code: 2 };
  audios[0].dispatchEvent(new Event("error"));
  await player.playItem(track("b"));
  finishRecovery(Response.json({ ok: true }));
  await flush();
  assert.equal(audios[0].src, "/audio-stream/b");
  assert.equal(audios[0].currentTime, 0);
  assert.equal(player.getSnapshot().trackState, "Playing");
});

test("a network error while paused does not resume without permission", async (t) => {
  const { player, audios, fetchMock } = await setup(t);
  await player.playItem(track("a"));
  audios[0].currentTime = 50;
  await player.playPause();
  audios[0].error = { code: 2 };
  audios[0].dispatchEvent(new Event("error"));
  await flush();
  assert.equal(fetchMock.mock.callCount(), 1);
  assert.equal(audios[0].paused, true);
  await player.playPause();
  assert.equal(audios[0].currentTime, 50);
});

test("recovery remembers progress even if the media element resets its clock on error", async (t) => {
  const { player, audios } = await setup(t);
  await player.playItem(track("a"));
  audios[0].currentTime = 60;
  audios[0].dispatchEvent(new Event("timeupdate"));
  audios[0].currentTime = 0;
  audios[0].error = { code: 2 };
  audios[0].dispatchEvent(new Event("error"));
  await flush();
  assert.equal(audios[0].currentTime, 60);
});
