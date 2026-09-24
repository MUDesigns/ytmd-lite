import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import ts from "typescript";

const source = await readFile(new URL("../src/lib/api.ts", import.meta.url), "utf8");
const { outputText } = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
});
const { loadExplore, loadArtistRadio, browseDetail } = await import(
  `data:text/javascript;base64,${Buffer.from(outputText).toString("base64")}`
);
function mockApi(t, routes) {
  const calls = [];
  t.mock.method(globalThis, "fetch", async url => {
    const path = new URL(url).pathname + new URL(url).search;
    calls.push(path);
    return Response.json(routes[path] ?? { error: "Unavailable" }, { status: routes[path] ? 200 : 500 });
  });
  return calls;
}

test("Artist radio uses the watch playlist and deduplicates playable tracks", async t => {
  const track = { videoId: "radio-song", title: "Radio song" };
  const calls = mockApi(t, {
    "/artist/UC-seed": { name: "Seed", radioId: "RD-artist", tracks: [] },
    "/radio/RD-artist": { tracks: [track, track, { title: "Unavailable" }] },
  });
  assert.deepEqual((await loadArtistRadio("UC-seed")).map(t => t.videoId), ["radio-song"]);
  assert.deepEqual(calls, ["/artist/UC-seed", "/radio/RD-artist"]);
});

test("Artist radio falls back to a top song when no radio ID exists", async t => {
  mockApi(t, {
    "/artist/UC-seed": { tracks: [{ videoId: "seed", title: "Seed" }] },
    "/radio/seed?videoId=seed": { tracks: [{ videoId: "related", title: "Related" }] },
  });
  assert.equal((await loadArtistRadio("UC-seed"))[0].videoId, "related");
});

test("An empty artist radio reports an actionable error", async t => {
  mockApi(t, { "/artist/UC-empty": { tracks: [] } });
  await assert.rejects(loadArtistRadio("UC-empty"), /No radio tracks/);
});

test("Discover uses recent plays, hides heard tracks, and exposes related artists", async t => {
  const fresh = { videoId: "fresh", title: "New discovery" };
  const calls = mockApi(t, {
    "/radio/seed?videoId=recent": { tracks: [
      { videoId: "recent", title: "Recent favorite", artists: "Seed artist", artistBrowseId: "UC-seed" },
      { videoId: "older", title: "Already heard" }, fresh, fresh,
    ] },
    "/radio/seed?videoId=older": { tracks: [fresh, { videoId: "second", title: "Another discovery" }] },
    "/artist/UC-seed": { related: [
      { browseId: "UC-related", title: "Related artist" },
      { browseId: "UC-related", title: "Related artist" },
      { browseId: "UC-seed", title: "Seed artist" },
    ] },
  });
  const result = await loadExplore(["older", "recent"]);
  assert.equal(result.shelves[0].title, "Because you played Recent favorite");
  assert.deepEqual(result.shelves.flatMap(s => s.items.map(t => t.id)), ["fresh", "second", "UC-related"]);
  assert.equal(result.shelves[2].items[0].type, "artist");
  assert.deepEqual(result.items.map(t => t.videoId), ["fresh", "second"], "Playback queue follows visible recommendations and excludes artists");
  assert.ok(!calls.some(path => path.includes("mood") || path.includes("liked")));
});

test("Discover falls back to likes and explains how new listeners can get started", async t => {
  mockApi(t, {
    "/liked?limit=30": { tracks: [{ videoId: "liked", title: "Favorite" }] },
    "/radio/seed?videoId=liked": { tracks: [{ videoId: "new", title: "Discovery" }] },
  });
  assert.equal((await loadExplore()).shelves[0].title, "Because you like Favorite");
  mockApi(t, { "/liked?limit=30": { tracks: [] } });
  const empty = await loadExplore();
  assert.equal(empty.shelves.length, 0);
  assert.deepEqual(empty.items, []);
  assert.match(empty.subtitle, /Play some songs/);
});

test("Discover preserves successful recommendations when a seed fails", async t => {
  mockApi(t, { "/radio/seed?videoId=good": { tracks: [{ videoId: "new", title: "Discovery" }] } });
  assert.equal((await loadExplore(["bad", "good"])).shelves.length, 1);
  await assert.rejects(loadExplore(["bad"]), /Try refreshing Discover/);
});

test("Discover only deduplicates displayed tracks and limits seed requests", async t => {
  const tracks = Array.from({ length: 15 }, (_, i) => ({ videoId: `new-${i}`, title: `Discovery ${i}` }));
  const calls = mockApi(t, {
    "/radio/seed?videoId=latest": { tracks },
    "/radio/seed?videoId=previous": { tracks },
    "/radio/seed?videoId=third": { tracks: [] },
  });
  const result = await loadExplore(["old", "third", "previous", "latest"]);
  assert.deepEqual(result.shelves.map(s => s.items.length), [12, 3]);
  assert.deepEqual(result.items.map(t => t.videoId), tracks.map(t => t.videoId));
  assert.equal(calls.length, 3);
});

test("Artist details expose radio metadata and unique related artists", async t => {
  const related = { browseId: "UC-related", title: "Related artist" };
  mockApi(t, { "/artist/UC-seed": { radioId: "RD-seed", related: [related, related] } });
  const artist = await browseDetail({ browseId: "UC-seed" });
  assert.equal(artist.radioId, "RD-seed");
  assert.equal(artist.shelves[0].title, "Fans might also like");
  assert.equal(artist.shelves[0].items.length, 1);
});
