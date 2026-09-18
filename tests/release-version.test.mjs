import assert from "node:assert/strict";
import { test } from "node:test";
import { releaseVersion } from "../scripts/release-version.mjs";

test("main builds have increasing stable versions, including reruns", () => {
  assert.equal(releaseVersion("0.2.1", "6"), "0.2.7");
  assert.equal(releaseVersion("0.2.1", "7"), "0.2.8");
  assert.equal(releaseVersion("0.2.1", "6"), "0.2.7");
});
test("tag builds embed the exact requested stable version", () => {
  assert.equal(releaseVersion("0.2.1", "7", "v0.3.0"), "0.3.0");
  assert.throws(() => releaseVersion("0.2.1", "7", "v0.3.0-beta"));
});
test("invalid versions and MSI overflow stop the release", () => {
  assert.throws(() => releaseVersion("0.2.1", "0"));
  assert.throws(() => releaseVersion("0.2.1", "65535"));
  assert.throws(() => releaseVersion("0.2.1-beta", "7"));
});
