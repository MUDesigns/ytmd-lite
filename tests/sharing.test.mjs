import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import ts from "typescript";

const source = await readFile(new URL("../src/lib/sharing.ts", import.meta.url), "utf8");
const { outputText } = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext } });
const { songShareUrl, sharedSongId } = await import(`data:text/javascript;base64,${Buffer.from(outputText).toString("base64")}`);
const videoId = "dQw4w9WgXcQ";

test("share links point at the same song in each destination", () => {
  assert.equal(songShareUrl(videoId, "player"), `ytmd-lite://song/${videoId}`);
  assert.equal(songShareUrl(videoId, "music"), `https://music.youtube.com/watch?v=${videoId}`);
  assert.equal(songShareUrl(videoId, "youtube"), `https://www.youtube.com/watch?v=${videoId}`);
  assert.equal(sharedSongId(songShareUrl(videoId, "player")), videoId);
  assert.equal(sharedSongId("ytmd-lite://song/Abc_-123456"), "Abc_-123456");
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
