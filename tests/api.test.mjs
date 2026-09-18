import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import ts from "typescript";

// Exercise the API adapter without a webview or a signed-in YouTube account.
const source = await readFile(new URL("../src/lib/api.ts", import.meta.url), "utf8");
const { outputText } = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
});
const { loadLibrary, mapItem, browseDetail, search, searchLibrary } = await import(
  `data:text/javascript;base64,${Buffer.from(outputText).toString("base64")}`
);

function mockApi(t, routes) {
  const calls = [];
  t.mock.method(globalThis, "fetch", async (url) => {
    const path = new URL(url).pathname + new URL(url).search;
    calls.push(path);
    const body = routes[path];
    return Response.json(body ?? { error: `Unexpected request: ${path}` }, {
      status: body === undefined ? 500 : 200,
    });
  });
  return calls;
}

const libraryRoutes = {
  "/library/playlists": { playlists: [] },
  "/library/albums": { albums: [] },
  "/library/artists": { artists: [] },
};
const likedTracks = {
  title: "Liked Songs",
  tracks: [{ videoId: "liked-video", title: "A favorite", artists: "An artist" }],
};

test("Library exposes Liked Songs even when no regular playlists exist", async (t) => {
  const calls = mockApi(t, { ...libraryRoutes, "/playlist/LM": likedTracks });
  const library = await loadLibrary();
  const liked = library.shelves[0].items[0];
  assert.equal(library.shelves[0].title, "Playlists");
  assert.equal(liked.title, "Liked Songs");
  assert.equal(liked.type, "playlist");
  assert.equal(liked.playlistId, "LM");
  assert.equal(calls.length, 3, "Tracks should load only when the collection opens");
  const detail = await browseDetail(liked);
  assert.equal(detail.items[0].videoId, "liked-video");
  assert.equal(detail.items[0].type, "song");
  assert.equal(calls.at(-1), "/playlist/LM");
});

test("Library preserves existing Liked Songs metadata without adding a duplicate", async (t) => {
  mockApi(t, {
    ...libraryRoutes,
    "/library/playlists": {
      playlists: [
        { playlistId: "VLLM", title: "Liked Songs", count: "12" },
        { playlistId: "PL-other", title: "Road trip" },
      ],
    },
  });
  const library = await loadLibrary();
  const items = library.shelves[0].items;
  assert.equal(items.length, 2);
  assert.equal(items[0].playlistId, "LM");
  assert.equal(items[0].subtitle, "12 tracks");
  assert.equal(items[1].playlistId, "PL-other");
});

test("Liked Songs IDs open as playlists even with an album type hint", async (t) => {
  const calls = mockApi(t, { "/playlist/LM": likedTracks });
  for (const identity of [
    { browseId: "LM" },
    { browseId: "VLLM" },
    { playlistId: "LM" },
    { playlistId: "VLLM" },
  ]) {
    const item = mapItem({ ...identity, type: "album", title: "Liked Songs" });
    assert.equal(item.type, "playlist");
    assert.equal(item.playlistId, "LM");
    assert.equal((await browseDetail(identity)).items[0].videoId, "liked-video");
  }
  assert.deepEqual(calls, Array(4).fill("/playlist/LM"));
});

test("Search routes a Liked Songs collection to its tracks", async (t) => {
  mockApi(t, {
    "/search?q=liked+songs": {
      results: [{ type: "album", browseId: "LM", title: "Liked Songs" }],
    },
    "/playlist/LM": likedTracks,
  });
  const results = await search("liked songs");
  assert.equal(results.items[0].type, "playlist");
  assert.equal((await browseDetail(results.items[0])).items.length, 1);
});

test("Library search finds the Liked Songs collection by name", async (t) => {
  mockApi(t, { ...libraryRoutes, "/liked?limit=500": likedTracks });
  const results = await searchLibrary("liked songs");
  assert.equal(results.items.length, 1);
  assert.equal(results.items[0].playlistId, "LM");
});

test("An unrelated album named Liked Songs still opens as an album", async (t) => {
  const calls = mockApi(t, { "/album/MPRE-album": { title: "Liked Songs", tracks: [] } });
  const item = mapItem({ type: "album", browseId: "MPRE-album", title: "Liked Songs" });
  assert.equal(item.type, "album");
  await browseDetail(item);
  assert.deepEqual(calls, ["/album/MPRE-album"]);
});

test("Liked Songs load errors reach the UI instead of becoming an empty collection", async (t) => {
  mockApi(t, { "/playlist/LM": { error: "Please sign in again" } });
  await assert.rejects(browseDetail({ browseId: "LM" }), /Please sign in again/);
});
