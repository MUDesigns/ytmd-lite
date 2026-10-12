import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import { runInNewContext } from "node:vm";
import ts from "typescript";

const source = await readFile(new URL("../src/lib/sharing.ts", import.meta.url), "utf8");
const { outputText } = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext } });
const { songShareUrl, sharedSongId, playlistShareUrl, sharedPlaylistId, shareablePlaylistId } = await import(`data:text/javascript;base64,${Buffer.from(outputText).toString("base64")}`);
const videoId = "dQw4w9WgXcQ";
const playlistId = "PLabc_123-ExamplePlaylist";

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

test("playlist share links normalize browse IDs and preserve the playlist in each destination", () => {
  for (const id of [playlistId, "RDCLAK5uy_mix-123", "OLAK5uy_album-123"]) {
    assert.equal(playlistShareUrl(`VL${id}`, "player"), `https://mudesigns.github.io/ytmd-lite/?playlist=${id}`);
    assert.equal(playlistShareUrl(id, "music"), `https://music.youtube.com/playlist?list=${id}`);
    assert.equal(playlistShareUrl(id, "youtube"), `https://www.youtube.com/playlist?list=${id}`);
    assert.equal(sharedPlaylistId(`ytmd-lite://playlist/${id}`), id);
    assert.equal(sharedPlaylistId(`ytmd-lite://playlist/VL${id}`), id);
  }
});

test("playlist menus share only playlists with usable IDs, excluding personal Liked Songs", () => {
  assert.equal(shareablePlaylistId({ type: "playlist", id: "title", playlistId }), playlistId);
  assert.equal(shareablePlaylistId({ type: "playlist", id: "title", browseId: `VL${playlistId}` }), playlistId);
  assert.equal(shareablePlaylistId({ type: "playlist", id: playlistId }), playlistId);
  assert.equal(shareablePlaylistId({ type: "playlist", id: "LM" }), null);
  assert.equal(shareablePlaylistId({ type: "playlist", id: "VLLM" }), null);
  assert.equal(shareablePlaylistId({ type: "playlist", id: "", playlistId: "../invalid" }), null);
  assert.equal(shareablePlaylistId({ type: "song", id: videoId, playlistId }), null);
  assert.equal(shareablePlaylistId({ type: "album", id: "MPRE123", playlistId }), null);
  assert.equal(shareablePlaylistId(null), null);
});

test("playlist share page opens the same playlist in the app and offers browser alternatives", () => {
  const { elements, launches } = openSharePage(new URL(playlistShareUrl(playlistId, "player")).search);
  assert.deepEqual(launches, [`ytmd-lite://playlist/${playlistId}`]);
  assert.equal(sharedPlaylistId(launches[0]), playlistId);
  assert.equal(sharedSongId(launches[0]), null);
  assert.equal(elements.player.href, launches[0]);
  assert.equal(elements.music.href, playlistShareUrl(playlistId, "music"));
  assert.equal(elements.youtube.href, playlistShareUrl(playlistId, "youtube"));
  assert.equal(elements.heading.textContent, "A playlist for you.");
  assert.equal(elements.destinations.hidden, false);
  const blocked = openSharePage(`?playlist=${playlistId}`, true);
  assert.equal(blocked.elements.destinations.hidden, false);
  assert.equal(blocked.elements.player.href, launches[0]);
  assert.match(blocked.elements.status.textContent, /shared playlist/);
});

test("playlist links reject personal collections, malformed IDs, ambiguous parameters, and other routes", () => {
  for (const id of ["", "LM", "VLLM", "../../track", "PLabc?", "PLabc&list=other", "x".repeat(201)]) {
    assert.throws(() => playlistShareUrl(id, "player"), /shareable YouTube playlist ID/);
    assert.equal(sharedPlaylistId(`ytmd-lite://playlist/${id}`), null);
    const { elements, launches } = openSharePage(`?playlist=${encodeURIComponent(id)}`);
    assert.equal(elements.destinations.hidden, true, id);
    assert.deepEqual(launches, [], id);
  }
  for (const url of [`https://playlist/${playlistId}`, `ytmd-lite://song/${playlistId}`,
    `ytmd-lite://user@playlist/${playlistId}`, `ytmd-lite://playlist:123/${playlistId}`,
    `ytmd-lite://playlist/${playlistId}/extra`, `ytmd-lite://playlist/${playlistId}?list=other`,
    `ytmd-lite://playlist/${playlistId}#extra`, "not a url"]) {
    assert.equal(sharedPlaylistId(url), null, url);
  }
  for (const search of [`?playlist=${playlistId}&song=${videoId}`,
    `?playlist=${playlistId}&playlist=${playlistId}`, `?playlist=${playlistId}&song=`, "?playlist=PLabc%2Fextra"]) {
    const { elements, launches } = openSharePage(search);
    assert.equal(elements.destinations.hidden, true, search);
    assert.deepEqual(launches, [], search);
  }
});

const pageSource = await readFile(new URL("../src/routes/+page.svelte", import.meta.url), "utf8");
const pageScript = pageSource.match(/<script lang="ts">([\s\S]*?)<\/script>/)[1];
const pageAst = ts.createSourceFile("page.ts", pageScript, ts.ScriptTarget.Latest, true);
function pageFunction(name) {
  let found;
  function visit(node) {
    if ((ts.isFunctionDeclaration(node) || ts.isVariableDeclaration(node)) && node.name?.getText(pageAst) === name) {
      found = node;
    }
    ts.forEachChild(node, visit);
  }
  visit(pageAst);
  assert.ok(found, `Page must define ${name}`);
  const source = ts.isVariableDeclaration(found) ? `var ${found.getText(pageAst)};` : found.getText(pageAst);
  return ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
}

test("playlist share button and context menu copy playlist URLs for all destinations", async () => {
  const item = { type: "playlist", id: `VL${playlistId}`, browseId: `VL${playlistId}`, title: "Playlist" };
  const copied = [];
  const context = {
    detailItem: item, ctx: { open: false, item }, statusMsg: "", radioBusy: false, likedIds: new Set(),
    shareablePlaylistId, playlistShareUrl, songShareUrl,
    shareDestinations: [{ id: "player", label: "YTMD Lite" }, { id: "music", label: "YouTube Music" }, { id: "youtube", label: "YouTube" }],
    writeText: async value => copied.push(value),
  };
  runInNewContext(["shareActions", "openPlaylistShare", "copyItemLink", "contextActions", "onContextSelect"].map(pageFunction).join("\n"), context);
  context.openPlaylistShare({ currentTarget: { getBoundingClientRect: () => ({ left: 40, bottom: 80 }) } });
  assert.equal(context.ctx.shareOnly, true);
  assert.equal(context.ctx.item, item);
  assert.equal(context.ctx.y, 80);
  const actions = context.contextActions(item);
  for (const destination of ["player", "music", "youtube"]) {
    assert.ok(actions.some(action => action.id === `share-${destination}`));
    await context.onContextSelect(`share-${destination}`);
    assert.equal(copied.at(-1), playlistShareUrl(playlistId, destination));
    assert.equal(context.ctx.open, false);
  }
  assert.equal(context.contextActions({ ...item, id: "LM", browseId: "LM" }).some(action => action.id.startsWith("share-")), false);
  const song = { type: "song", id: videoId, videoId, title: "Song" };
  context.ctx = { open: true, item: song };
  await context.onContextSelect("share-player");
  assert.equal(copied.at(-1), songShareUrl(videoId, "player"));
});

test("incoming playlist links open detail without playing and supersede a pending shared song", async () => {
  const opened = [];
  const played = [];
  let finishSong;
  const context = {
    mounted: true, sharedRequest: 0, statusMsg: "", sharedSongId, sharedPlaylistId,
    loadSharedSong: () => new Promise(resolve => { finishSong = resolve; }),
    openItem: async item => opened.push(item), playFromUi: async item => played.push(item),
  };
  runInNewContext(pageFunction("openSharedUrls"), context);
  const pendingSong = context.openSharedUrls([`ytmd-lite://song/${videoId}`]);
  await context.openSharedUrls(["invalid", `ytmd-lite://playlist/${playlistId}`]);
  assert.equal(opened.length, 1);
  assert.equal(opened[0].type, "playlist");
  assert.equal(opened[0].playlistId, playlistId);
  assert.deepEqual(played, []);
  finishSong({ videoId });
  await pendingSong;
  assert.deepEqual(played, []);
  await context.openSharedUrls(["ytmd-lite://playlist/LM"]);
  assert.equal(opened.length, 1);
});
