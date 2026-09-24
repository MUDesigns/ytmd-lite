import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import ts from "typescript";

const source = await readFile(new URL("../src/lib/mini-player.ts", import.meta.url), "utf8");
const { outputText } = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext } });
const { miniPlayerUpdate } = await import(`data:text/javascript;base64,${Buffer.from(outputText).toString("base64")}`);

test("mini player receives like changes without copying the queue into frequent updates", () => {
  const player = { videoDetails: { id: "song" }, trackState: "Playing", likeStatus: "LIKE", queue: Array(1000).fill({ videoId: "song" }) };
  const update = miniPlayerUpdate(player, "#123456");
  assert.equal(update.player.likeStatus, "LIKE");
  assert.equal(update.player.queue, undefined);
  assert.equal(update.accent, "#123456");
  player.likeStatus = "INDIFFERENT";
  assert.equal(miniPlayerUpdate(player, "#123456").player.likeStatus, "INDIFFERENT");
});
