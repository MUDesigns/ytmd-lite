import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import ts from "typescript";

const listeningSource = await readFile(new URL("../src/lib/listening.ts", import.meta.url), "utf8");
const listeningCode = ts.transpileModule(listeningSource, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText;
const listeningUrl = `data:text/javascript;base64,${Buffer.from(listeningCode).toString("base64")}`;
const apiSource = await readFile(new URL("../src/lib/api.ts", import.meta.url), "utf8");
const apiCode = ts.transpileModule(apiSource, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText;
const apiUrl = `data:text/javascript;base64,${Buffer.from(apiCode).toString("base64")}`;

const source = (await readFile(new URL("../src/lib/player.ts", import.meta.url), "utf8"))
  .replace('from "./listening"', `from "${listeningUrl}"`)
  .replace('import { invoke } from "@tauri-apps/api/core";', 'const invoke = async () => {};')
  .replace('import { API_BASE, streamUrl, loadSongRadio } from "./api";',
    `import { loadSongRadio } from "${apiUrl}"; const API_BASE = "http://localhost"; const streamUrl = id => \`/audio-stream/\${id}\`;`);
const { outputText } = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
});
let instance = 0;
const track = (videoId) => ({ videoId, title: videoId });
const flush = () => new Promise((resolve) => setImmediate(resolve));

test("progress snapshots reuse queue and metadata until their inputs change", async (t) => {
  const { player, audios } = await setup(t);
  await player.playItems(Array.from({ length: 1000 }, (_, i) => track(`track-${i}`)));
  const before = player.getSnapshot();
  const started = performance.now();
  let queueChanges = 0;
  let metadataChanges = 0;
  for (let i = 0; i < 2000; i++) {
    audios[0].currentTime = i / 2;
    const state = player.getSnapshot();
    if (state.queue !== before.queue) queueChanges++;
    if (state.videoDetails !== before.videoDetails) metadataChanges++;
    assert.equal(state.videoProgress, i / 2);
  }
  t.diagnostic(`2000 snapshots / 1000 tracks: ${(performance.now() - started).toFixed(2)}ms; queue replacements=${queueChanges}; metadata replacements=${metadataChanges}`);
  assert.equal(queueChanges, 0);
  assert.equal(metadataChanges, 0);

  await player.setVolume(42);
  assert.equal(player.getSnapshot().queue, before.queue);
  audios[0].duration = 240;
  assert.equal(player.getSnapshot().videoDetails.durationSeconds, 240);
  assert.equal(before.videoDetails.durationSeconds, 120);
  player.playNext(track("inserted"));
  assert.equal(before.queue.length, 1000, "published snapshots must stay unchanged");
  assert.equal(player.getSnapshot().queue[1].videoId, "inserted");
  await player.playQueueIndex(1);
  assert.equal(player.getSnapshot().queue[0].selected, false);
  assert.equal(player.getSnapshot().queue[1].selected, true);
  assert.equal(player.getSnapshot().videoDetails.id, "inserted");
  assert.equal(before.queue[0].selected, true);
});

test("progress ticks stay quiet while paused and resume with playback", async (t) => {
  const { player, audios } = await setup(t);
  await player.playItem(track("a"));
  const tick = globalThis.setInterval.mock.calls.at(-1).arguments[0];
  const updates = [];
  const unsubscribe = player.subscribe(state => updates.push(state));
  t.after(unsubscribe);
  await player.playPause();
  const pausedCount = updates.length;
  tick();
  tick();
  assert.equal(updates.length, pausedCount);
  await player.seek(12);
  assert.equal(updates.at(-1).videoProgress, 12, "seeking while paused still publishes");
  await player.playPause();
  audios[0].currentTime = 13;
  tick();
  assert.equal(updates.at(-1).videoProgress, 13);
  assert.equal(updates.at(-1).trackState, "Playing");
});

function mockStorage(t, value) {
  const original = Object.getOwnPropertyDescriptor(globalThis, "localStorage");
  Object.defineProperty(globalThis, "localStorage", { configurable: true, value });
  t.after(() => original ? Object.defineProperty(globalThis, "localStorage", original) : delete globalThis.localStorage);
}

test("volume survives a fresh player instance, including zero", async (t) => {
  const saved = new Map();
  mockStorage(t, {
    getItem: key => saved.get(key) ?? null,
    setItem: (key, value) => saved.set(key, value),
  });
  let { player } = await setup(t);
  assert.equal(player.getSnapshot().volume, 100);
  for (const volume of [37, 0]) {
    await player.handleMediaCommand(`volume:${volume}`);
    const reopened = await setup(t);
    player = reopened.player;
    assert.equal(player.getSnapshot().volume, volume);
    await player.playItem(track("a"));
    assert.equal(reopened.audios[0].volume, volume / 100);
  }
});

test("invalid or unavailable volume storage does not prevent playback", async (t) => {
  let raw;
  mockStorage(t, {
    getItem: () => {
      if (raw === undefined) throw new Error("unavailable");
      return raw;
    },
    setItem: () => { throw new Error("unavailable"); },
  });
  for (raw of ["invalid", "null", "-1", "101", '"42"']) {
    const { player } = await setup(t);
    assert.equal(player.getSnapshot().volume, 100);
  }
  raw = undefined;
  const { player, audios } = await setup(t);
  await player.setVolume(25);
  await player.playItem(track("a"));
  assert.equal(audios[0].volume, .25);
});

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

test("autoplay appends unique radio songs after the explicit queue and continues playback", async t => {
  const { player, audios, fetchMock } = await setup(t, async url => Response.json(
    new URL(url).pathname.startsWith("/radio/")
      ? { tracks: [track("a"), track("b"), track("similar"), track("similar"), track("later")] }
      : { ok: true }));
  player.setAutoplay(true);
  await player.playItems([track("a"), track("b")]);
  assert.equal(fetchMock.mock.calls.filter(c => c.arguments[0].includes("/radio/")).length, 0);
  await player.next();
  await flush();
  assert.deepEqual(player.getSnapshot().queue.map(t => t.videoId), ["a", "b", "similar", "later"]);
  assert.equal(player.getSnapshot().queue[2].autoplay, true);
  player.addToQueue(track("manual"));
  assert.deepEqual(player.getSnapshot().queue.map(t => t.videoId), ["a", "b", "manual", "similar", "later"]);
  audios[0].dispatchEvent(new Event("ended"));
  await flush();
  assert.equal(player.getSnapshot().videoDetails.id, "manual");
  await player.next();
  assert.equal(player.getSnapshot().videoDetails.id, "similar");
  player.setAutoplay(false);
  assert.deepEqual(player.getSnapshot().queue.map(t => t.videoId), ["a", "b", "manual", "similar"]);
  assert.equal(audios[0].paused, false);
});

test("autoplay preference persists across player instances", async t => {
  const saved = new Map();
  mockStorage(t, { getItem: key => saved.get(key) ?? null, setItem: (key, value) => saved.set(key, value) });
  const { player } = await setup(t);
  assert.equal(player.getSnapshot().autoplay, false);
  player.setAutoplay(true);
  assert.equal((await setup(t)).player.getSnapshot().autoplay, true);
  player.setAutoplay(false);
  assert.equal((await setup(t)).player.getSnapshot().autoplay, false);
});

test("late autoplay responses cannot change a cleared, replaced, or disabled queue", async t => {
  for (const action of ["clear", "replace", "disable"]) {
    let finish;
    const { player } = await setup(t, url => new URL(url).pathname.startsWith("/radio/")
      ? new Promise(resolve => { finish = resolve; }) : Promise.resolve(Response.json({ ok: true })));
    player.setAutoplay(true);
    await player.playItem(track("old"));
    await flush();
    if (action === "clear") player.clearQueue();
    else if (action === "disable") player.setAutoplay(false);
    else await player.playItems([track("replacement"), track("second")]);
    finish(Response.json({ tracks: [track("stale")] }));
    await flush();
    assert.ok(!player.getSnapshot().queue.some(t => t.videoId === "stale"));
    assert.equal(player.getSnapshot().autoplayLoading, false);
  }
});

test("pausing while waiting for autoplay does not start the returned song", async t => {
  let finish;
  const { player, audios } = await setup(t, url => new URL(url).pathname.startsWith("/radio/")
    ? new Promise(resolve => { finish = resolve; }) : Promise.resolve(Response.json({ ok: true })));
  player.setAutoplay(true);
  await player.playItem(track("seed"));
  const advancing = player.next();
  await player.playPause();
  finish(Response.json({ tracks: [track("similar")] }));
  await advancing;
  assert.equal(player.getSnapshot().videoDetails.id, "seed");
  assert.equal(audios[0].paused, true);
  assert.equal(player.getSnapshot().queue.length, 2);
});

test("autoplay failures stop at the queue end without retry loops", async t => {
  const { player, fetchMock } = await setup(t, async url => new URL(url).pathname.startsWith("/radio/")
    ? Response.json({ error: "offline" }, { status: 503 }) : Response.json({ ok: true }));
  player.setAutoplay(true);
  await player.playItem(track("seed"));
  await flush();
  await player.next();
  assert.equal(player.getSnapshot().trackState, "Paused");
  assert.match(player.getSnapshot().autoplayError, /offline/);
  assert.equal(fetchMock.mock.calls.filter(c => c.arguments[0].includes("/radio/")).length, 1);
});

test("shuffle exhausts original tracks before autoplay and repeat prevents extension", async t => {
  const { player, fetchMock } = await setup(t, async url => Response.json(new URL(url).pathname.startsWith("/radio/")
    ? { tracks: [track("similar"), track("later")] } : { ok: true }));
  player.setAutoplay(true);
  await player.playItems([track("a"), track("b"), track("c")]);
  player.toggleShuffle();
  const heard = [player.getSnapshot().videoDetails.id];
  await player.next(); heard.push(player.getSnapshot().videoDetails.id);
  await player.next(); heard.push(player.getSnapshot().videoDetails.id);
  await flush();
  assert.equal(new Set(heard).size, 3);
  await player.next();
  assert.equal(player.getSnapshot().videoDetails.id, "similar");
  player.setAutoplay(false);
  player.clearQueue();
  player.toggleShuffle();
  player.toggleRepeat();
  player.setAutoplay(true);
  const before = fetchMock.mock.callCount();
  await player.playItem(track("repeat"));
  await player.next();
  assert.ok(fetchMock.mock.calls.slice(before).every(c => !c.arguments[0].includes("/radio/")));
});

test("moving queue entries preserves the exact current entry, progress, and next track", async t => {
  const { player, audios } = await setup(t);
  await player.playItems([track("same"), track("current"), track("same"), track("last")], 1);
  audios[0].currentTime = 37;
  const src = audios[0].src;
  player.moveQueueItem(3, 2);
  player.moveQueueItem(0, 3);
  assert.equal(player.getSnapshot().queueIndex, 0);
  assert.equal(player.getSnapshot().videoDetails.id, "current");
  assert.equal(audios[0].currentTime, 37);
  assert.equal(audios[0].src, src);
  assert.deepEqual(player.getSnapshot().queue.map(t => t.videoId), ["current", "last", "same", "same"]);
  await player.next();
  assert.equal(player.getSnapshot().videoDetails.id, "last");
});

test("autoplay keeps extending successive batches and respects stop after album", async t => {
  let batch = 0;
  const { player, audios, fetchMock } = await setup(t, async url => Response.json(new URL(url).pathname.startsWith("/radio/")
    ? { tracks: [track(`suggestion-${++batch}`)] } : { ok: true }));
  player.setAutoplay(true);
  await player.playItem(track("seed"));
  await flush();
  await player.next();
  await flush();
  await player.next();
  await flush();
  assert.equal(player.getSnapshot().videoDetails.id, "suggestion-2");
  assert.equal(player.getSnapshot().queue.at(-1).videoId, "suggestion-3");
  await player.playItems([{ ...track("album-first"), albumId: "album" }, { ...track("album-last"), albumId: "album" }]);
  player.setStopAfterAlbum(true);
  const before = fetchMock.mock.callCount();
  await player.next();
  audios[0].dispatchEvent(new Event("ended"));
  await flush();
  assert.equal(player.getSnapshot().trackState, "Paused");
  assert.ok(fetchMock.mock.calls.slice(before).every(c => !c.arguments[0].includes("/radio/")));
});

test("reordering in shuffle preserves remaining choices without replaying the current song", async t => {
  const { player, audios } = await setup(t);
  await player.playItems([track("a"), track("b"), track("c")]);
  player.toggleShuffle();
  audios[0].currentTime = 22;
  player.moveQueueItem(0, 2);
  assert.equal(player.getSnapshot().queueIndex, 2);
  assert.equal(audios[0].currentTime, 22);
  const played = [];
  await player.next(); played.push(player.getSnapshot().videoDetails.id);
  await player.next(); played.push(player.getSnapshot().videoDetails.id);
  assert.deepEqual(played.sort(), ["b", "c"]);
  await player.next();
  assert.equal(player.getSnapshot().trackState, "Paused");
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
