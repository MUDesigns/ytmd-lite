import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import { runInNewContext } from "node:vm";
import ts from "typescript";

const source = await readFile(new URL("../src/lib/sharing.ts", import.meta.url), "utf8");
const { outputText } = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext } });
const { songShareUrl, sharedSongId } = await import(`data:text/javascript;base64,${Buffer.from(outputText).toString("base64")}`);
const videoId = "dQw4w9WgXcQ";

test("share links point at the same song in each destination", () => {
  assert.equal(songShareUrl(videoId, "player"), `https://mudesigns.github.io/ytmd-lite/?song=${videoId}`);
  assert.equal(songShareUrl(videoId, "music"), `https://music.youtube.com/watch?v=${videoId}`);
  assert.equal(songShareUrl(videoId, "youtube"), `https://www.youtube.com/watch?v=${videoId}`);
  assert.equal(sharedSongId(`ytmd-lite://song/${videoId}`), videoId);
  assert.equal(sharedSongId("ytmd-lite://song/Abc_-123456"), "Abc_-123456");
});

const bridgeSource = await readFile(new URL("../share-site/share.js", import.meta.url), "utf8");

function openSharePage(search, blocked = false) {
  const elements = Object.fromEntries(["player", "music", "youtube", "heading", "status", "destinations"]
    .map((id) => [id, { hidden: id === "destinations" }]));
  const launches = [];
  runInNewContext(bridgeSource, {
    URLSearchParams,
    document: { getElementById: (id) => elements[id] },
    window: { location: { search, assign: (url) => {
      if (blocked) throw new Error("User gesture required");
      launches.push(url);
    } } },
  });
  return { elements, launches };
}

test("Discord-friendly share URL bridges to the same song in the installed app and browsers", () => {
  const { elements, launches } = openSharePage(new URL(songShareUrl(videoId, "player")).search);
  assert.deepEqual(launches, [`ytmd-lite://song/${videoId}`]);
  assert.equal(sharedSongId(launches[0]), videoId);
  assert.equal(elements.player.href, launches[0]);
  assert.equal(elements.music.href, songShareUrl(videoId, "music"));
  assert.equal(elements.youtube.href, songShareUrl(videoId, "youtube"));
  assert.equal(elements.destinations.hidden, false);
});

test("share page retains clickable choices when automatic app launch is blocked", () => {
  const { elements, launches } = openSharePage(`?song=${videoId}`, true);
  assert.deepEqual(launches, []);
  assert.equal(elements.player.href, `ytmd-lite://song/${videoId}`);
  assert.equal(elements.destinations.hidden, false);
  assert.match(elements.status.textContent, /Choose where/);
});

test("share page never launches an app or enables links for invalid or ambiguous songs", () => {
  for (const search of ["", "?song=", "?song=short", "?song=abcdefghij%2F",
    "?song=javascript%3Aalert(1)", `?song=${videoId}&song=${videoId}`]) {
    const { elements, launches } = openSharePage(search);
    assert.deepEqual(launches, [], search);
    assert.equal(elements.destinations.hidden, true, search);
    assert.equal(elements.player.href, undefined, search);
  }
});

test("incoming links reject other protocols, routes, and invalid IDs", () => {
  for (const value of ["", "not a url", `https://song/${videoId}`, `kodama://song/${videoId}`,
    `ytmd-lite://playlist/${videoId}`, `ytmd-lite://song/${videoId}/extra`,
    `ytmd-lite://user@ song/${videoId}`, `ytmd-lite://user@song/${videoId}`,
    "ytmd-lite://song/short", "ytmd-lite://song/abcdefghij%2F", "ytmd-lite://song/"]) {
    assert.equal(sharedSongId(value), null, value);
  }
});

test("invalid song IDs cannot be copied into share links", () => {
  for (const value of ["", "short", "../../track", "abcdefghij?", `${videoId}&list=other`]) {
    assert.throws(() => songShareUrl(value, "player"), /valid YouTube video ID/);
  }
});
