import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import ts from "typescript";

// Compile the offline module with its api.ts dependency and svelte/store stubbed in memory.
async function load(file) {
  const source = await readFile(new URL(`../src/lib/${file}`, import.meta.url), "utf8");
  return ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText;
}
const apiUrl = `data:text/javascript;base64,${Buffer.from(await load("api.ts")).toString("base64")}`;
const storeSource = `
  export function writable(value) {
    const subs = new Set();
    return {
      set(v) { value = v; subs.forEach((s) => s(value)); },
      update(fn) { this.set(fn(value)); },
      subscribe(fn) { subs.add(fn); fn(value); return () => subs.delete(fn); },
    };
  }
  export function get(store) { let v; store.subscribe((x) => (v = x))(); return v; }
`;
const storeUrl = `data:text/javascript;base64,${Buffer.from(storeSource).toString("base64")}`;
const offlineSource = (await load("offline.ts"))
  .replace('from "./api"', `from "${apiUrl}"`)
  .replace('from "svelte/store"', `from "${storeUrl}"`);
const {
  offline, collectionKeyFor, isDownloaded, collectionProgress, downloadCollection, downloadSong, refreshLibrary,
  offlineCollectionItem, offlineShelves, isOfflineError, formatBytes, removeCollection,
} = await import(`data:text/javascript;base64,${Buffer.from(offlineSource).toString("base64")}`);
const { get } = await import(storeUrl);

const library = {
  songs: [
    { videoId: "a", title: "Alpha", artists: "Band", album: "LP", albumBrowseId: "MPRE1", thumbnail: "https://img/a", size: 1024 * 1024 },
    { videoId: "b", title: "Beta", artists: "Band", size: 2 * 1024 * 1024 },
  ],
  collections: [{
    key: "album:MPRE1", kind: "album", id: "MPRE1", title: "LP", subtitle: "Band", thumbnail: "https://img/lp",
    videoIds: ["a", "c"], tracks: [{ videoId: "a", title: "Alpha" }, { videoId: "c", title: "Gamma" }],
    done: 1, failed: 0, total: 2, size: 1024 * 1024,
  }],
  downloads: { c: { status: "downloading", progress: 0.4, title: "Gamma" } },
  generation: 3, active: 1, queued: 0, failed: 0, totalBytes: 3 * 1024 * 1024, dir: "C:/data/song_cache",
};

function mockApi(t, handlers) {
  const calls = [];
  t.mock.method(globalThis, "fetch", async (url, init = {}) => {
    const { pathname } = new URL(url);
    calls.push({ pathname, method: init.method || "GET", body: init.body ? JSON.parse(init.body) : undefined });
    const handler = handlers[`${init.method || "GET"} ${pathname}`] ?? handlers[pathname];
    if (!handler) return Response.json({ error: `unexpected ${pathname}` }, { status: 404 });
    return Response.json(typeof handler === "function" ? handler(calls.at(-1)) : handler);
  });
  return calls;
}

test("collection keys follow the item type and strip playlist prefixes", () => {
  assert.equal(collectionKeyFor({ type: "playlist", id: "x", title: "", thumbnails: [], playlistId: "PL1" }), "playlist:PL1");
  assert.equal(collectionKeyFor({ type: "playlist", id: "x", title: "", thumbnails: [], browseId: "VLPL2" }), "playlist:PL2");
  assert.equal(collectionKeyFor({ type: "album", id: "x", title: "", thumbnails: [], browseId: "MPRE1" }), "album:MPRE1");
  assert.equal(collectionKeyFor({ type: "artist", id: "x", title: "", thumbnails: [], browseId: "UC1" }), "artist:UC1");
  assert.equal(collectionKeyFor({ type: "song", id: "x", title: "", thumbnails: [], videoId: "v" }), null);
  assert.equal(collectionKeyFor({ type: "mood", id: "x", title: "", thumbnails: [], params: "p" }), null);
  assert.equal(collectionKeyFor({ type: "playlist", id: "x", title: "", thumbnails: [], offlineKey: "queue:1", items: [] }), "queue:1");
});

test("the library refresh exposes downloaded ids, collection progress and shelves", async (t) => {
  mockApi(t, { "/offline/library": library });
  await refreshLibrary();
  const state = get(offline);
  assert.deepEqual([...state.downloadedIds], ["a", "b"]);
  assert.deepEqual([...state.collectionKeys], ["album:MPRE1"]);
  assert.equal(state.active, 1);
  const album = { type: "album", id: "MPRE1", title: "LP", thumbnails: [], browseId: "MPRE1" };
  assert.equal(isDownloaded(state, album), true);
  assert.equal(isDownloaded(state, { type: "song", id: "a", title: "", thumbnails: [], videoId: "a" }), true);
  assert.equal(isDownloaded(state, { type: "song", id: "c", title: "", thumbnails: [], videoId: "c" }), false);
  assert.deepEqual(collectionProgress(state, album), { done: 1, total: 2, failed: 0, pending: 1 });
  assert.equal(collectionProgress(state, { type: "album", id: "y", title: "", thumbnails: [], browseId: "MPRE9" }), null);

  const shelves = offlineShelves(state);
  assert.deepEqual(shelves.map((s) => s.id), ["offline-collections", "offline-songs"]);
  const collection = shelves[0].items[0];
  assert.equal(collection.offlineKey, "album:MPRE1");
  assert.equal(collection.type, "album");
  assert.equal(collection.browseId, "MPRE1");
  // Only tracks already on disk are playable from the offline copy.
  assert.deepEqual(collection.items.map((i) => i.videoId), ["a"]);
  assert.equal(shelves[1].items[0].album, "LP");
  assert.equal(shelves[1].items[0].albumId, "MPRE1");
  assert.equal(shelves[1].items[0].subtitle, "Band");
});

test("a saved queue becomes a playlist-like item with a readable fallback title", () => {
  const item = offlineCollectionItem({
    key: "queue:1", kind: "queue", id: "1", videoIds: ["a"], tracks: [{ videoId: "a", title: "Alpha", thumbnail: "cover" }],
    done: 1, failed: 0, total: 1, size: 1,
  }, new Set(["a"]));
  assert.equal(item.type, "playlist");
  assert.equal(item.title, "Saved queue");
  assert.equal(item.subtitle, "1 of 1 downloaded");
  assert.deepEqual(item.thumbnails, ["cover"]);
  assert.equal(item.playlistId, undefined);
});

test("downloading a collection sends only playable track metadata and refreshes", async (t) => {
  const calls = mockApi(t, {
    "POST /offline/collections": ({ body }) => ({ ok: true, key: `${body.kind}:${body.id}`, queued: body.tracks.length, total: body.tracks.length }),
    "/offline/library": library,
  });
  const result = await downloadCollection("playlist", "PL1", [
    { type: "song", id: "a", title: "Alpha", subtitle: "Band", thumbnails: ["t"], videoId: "a", album: "LP", albumId: "MPRE1" },
    { type: "album", id: "MPRE1", title: "Not a track", thumbnails: [], browseId: "MPRE1" },
    { videoId: "q", title: "Queued", author: "Artist", thumbnails: ["qt"], selected: false, channelId: "UC9" },
  ], { title: "Mix", thumbnail: "cover" });
  assert.deepEqual(result, { key: "playlist:PL1", queued: 2, total: 2 });
  const post = calls.find((c) => c.pathname === "/offline/collections");
  assert.equal(post.body.title, "Mix");
  assert.deepEqual(post.body.tracks, [
    { videoId: "a", title: "Alpha", artists: "Band", album: "LP", albumBrowseId: "MPRE1", thumbnail: "t" },
    { videoId: "q", title: "Queued", artists: "Artist", artistBrowseId: "UC9", thumbnail: "qt" },
  ]);
  assert.ok(calls.some((c) => c.pathname === "/offline/library"), "library is refreshed after queuing");
  await assert.rejects(downloadCollection("album", "x", [{ type: "album", id: "x", title: "", thumbnails: [] }], {}), /no playable tracks/);
});

test("single-song downloads report the backend status and errors surface", async (t) => {
  mockApi(t, {
    "POST /offline/songs/a": { ok: true, status: "done" },
    "POST /offline/songs/b": { error: "Missing video id" },
  });
  assert.equal(await downloadSong({ type: "song", id: "a", title: "Alpha", thumbnails: [], videoId: "a" }), "done");
  await assert.rejects(downloadSong({ type: "song", id: "b", title: "Beta", thumbnails: [], videoId: "b" }), /Missing video id/);
  await assert.rejects(downloadSong({ type: "album", id: "x", title: "", thumbnails: [] }), /no playable track/);
});

test("removing a collection uses the encoded key and reloads", async (t) => {
  const calls = mockApi(t, { "DELETE /offline/collections/album%3AMPRE1": { ok: true, removed: 1 }, "/offline/library": { ...library, collections: [] } });
  await removeCollection("album:MPRE1");
  assert.equal(calls[0].method, "DELETE");
  assert.deepEqual([...get(offline).collectionKeys], []);
});

test("offline detection and size formatting", () => {
  assert.equal(isOfflineError("HTTPSConnectionPool(host='music.youtube.com'): Max retries exceeded (NameResolutionError)"), true);
  assert.equal(isOfflineError("[Errno 11001] getaddrinfo failed"), true);
  assert.equal(isOfflineError("Catalog API unreachable on :9847 — restart the app"), false);
  assert.equal(isOfflineError("Playlist unavailable or private"), false);
  assert.equal(formatBytes(0), "0 MB");
  assert.equal(formatBytes(5.25 * 1024 * 1024), "5.3 MB");
  assert.equal(formatBytes(3 * 1024 * 1024 * 1024), "3.00 GB");
});
