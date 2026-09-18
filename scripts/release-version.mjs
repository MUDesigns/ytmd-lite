import { readFileSync, writeFileSync, appendFileSync } from "node:fs";
import { pathToFileURL } from "node:url";

export function releaseVersion(base, runNumber, tag) {
  const stable = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/;
  if (tag) {
    const version = tag.replace(/^v/, "");
    if (!stable.test(version)) throw new Error("Release tags must be stable versions (vX.Y.Z)");
    return version;
  }
  if (!stable.test(base) || !/^[1-9]\d*$/.test(String(runNumber))) {
    throw new Error("A stable base version and positive workflow run number are required");
  }
  const [major, minor, patch] = base.split(".").map(Number);
  const nextPatch = patch + Number(runNumber);
  if (major > 255 || minor > 255 || nextPatch > 65535) {
    throw new Error("Version exceeds Windows MSI limits; increase the base minor version");
  }
  return `${major}.${minor}.${nextPatch}`;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const configPath = "src-tauri/tauri.conf.json";
  const config = JSON.parse(readFileSync(configPath, "utf8"));
  const version = releaseVersion(config.version, process.env.GITHUB_RUN_NUMBER,
    process.env.GITHUB_REF_TYPE === "tag" ? process.env.GITHUB_REF_NAME : undefined);
  config.version = version;
  writeFileSync(configPath, JSON.stringify(config, null, 2) + "\n");
  appendFileSync(process.env.GITHUB_OUTPUT, `version=${version}\ntag=v${version}\n`);
}
