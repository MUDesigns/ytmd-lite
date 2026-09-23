import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import ts from "typescript";
const { outputText } = ts.transpileModule(await readFile(new URL("../src/lib/listening.ts", import.meta.url), "utf8"), {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
});
const { arrangeQueue, sessionFromState, writeSessions, readSessions, readHeard, rememberHeard } = await import(`data:text/javascript;base64,${Buffer.from(outputText).toString("base64")}`);
const item = (videoId, author = "", albumId) => ({ videoId, title: videoId, author, albumId, thumbnails: [], selected: false });
const rules = { noRepeatArtists: false, unplayedOnly: false, keepAlbums: false };
function storage(t) {
  const entries = new Map();
  const previous = Object.getOwnPropertyDescriptor(globalThis, "localStorage");
  Object.defineProperty(globalThis, "localStorage", { configurable: true, value: { getItem: k => entries.get(k) || null, setItem: (k, v) => entries.set(k, v) } });
  t.after(() => previous ? Object.defineProperty(globalThis, "localStorage", previous) : delete globalThis.localStorage);
  return entries;
}
test("queue filters preserve the current track and history, while retaining unknown artists", () => {
  const queue = [item("past", "A"), item("current", "B"), item("a", "A"), item("heard", "C"), item("c", "C"), item("unknown")];
  const result = arrangeQueue(queue, 1, { ...rules, noRepeatArtists: true, unplayedOnly: true }, new Set(["current", "heard"]));
  assert.deepEqual(result.map(t => t.videoId), ["past", "current", "c", "unknown"]);
  assert.equal(queue.length, 6, "preview must not mutate the original queue");
});
test("album grouping continues the current album, preserves track order and keeps unknown albums separate", () => {
  const queue = [item("a1", "A", "a"), item("b1", "B", "b"), item("a2", "A", "a"), item("u1"), item("b2", "B", "b"), item("u2")];
  assert.deepEqual(arrangeQueue(queue, 0, { ...rules, keepAlbums: true }, new Set()).map(t => t.videoId), ["a1", "a2", "b1", "b2", "u1", "u2"]);
});
test("session persistence round-trips position, metadata and playback modes without sharing mutable queue state", t => {
  storage(t);
  const state = { queue: [item("a", "Artist", "album")], queueIndex: 0, videoProgress: 73.5, volume: 42, shuffle: false, repeat: "off", stopAfterAlbum: "album" };
  const session = sessionFromState(" Late night ", state);
  state.queue[0].title = "changed";
  writeSessions([session]);
  const [saved] = readSessions();
  assert.equal(saved.name, "Late night");
  assert.equal(saved.position, 73.5);
  assert.equal(saved.queue[0].title, "a");
  assert.equal(saved.stopAfterAlbum, "album");
  assert.equal(saved.volume, 42);
});
test("malformed saved sessions and history are ignored; storage failure is surfaced on save", t => {
  const entries = storage(t);
  entries.set("ytmd.sessions.v1", JSON.stringify([{ id: "bad", queue: [] }]));
  entries.set("ytmd.heard.v1", '{broken');
  assert.deepEqual(readSessions(), []);
  assert.equal(readHeard().size, 0);
  rememberHeard("a"); rememberHeard("a");
  assert.deepEqual([...readHeard()], ["a"]);
  localStorage.setItem = () => { throw new Error("Quota exceeded"); };
  assert.throws(() => writeSessions([]), /Could not save sessions/);
  assert.throws(() => sessionFromState("Empty", { queue: [] }), /Add music/);
});
