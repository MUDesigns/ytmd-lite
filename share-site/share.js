"use strict";

const params = new URLSearchParams(window.location.search);
const songIds = params.getAll("song");
const playlistIds = params.getAll("playlist");
const kind = playlistIds.length ? "playlist" : "song";
const ids = kind === "playlist" ? playlistIds : songIds;
const id = ids.length === 1 ? ids[0] : null;
const valid = id && !(songIds.length && playlistIds.length) && (kind === "song"
  ? /^[A-Za-z0-9_-]{11}$/.test(id)
  : /^[A-Za-z0-9_-]{2,200}$/.test(id) && id !== "LM" && !id.startsWith("VL"));

if (valid) {
  const playerUrl = `ytmd-lite://${kind}/${id}`;
  const browserPath = kind === "song" ? `watch?v=${id}` : `playlist?list=${id}`;
  document.getElementById("player").href = playerUrl;
  document.getElementById("music").href = `https://music.youtube.com/${browserPath}`;
  document.getElementById("youtube").href = `https://www.youtube.com/${browserPath}`;
  document.getElementById("destinations").hidden = false;
  document.getElementById("heading").textContent = kind === "playlist" ? "A playlist for you." : "A song for you.";
  document.getElementById("status").textContent = `Opening this ${kind} in YTMD Lite…`;
  // Some browsers require a click before launching an installed app. Keep the
  // explicit app button and browser links available regardless of the outcome.
  try {
    window.location.assign(playerUrl);
  } catch {
    document.getElementById("status").textContent = `Choose where to listen to this shared ${kind}.`;
  }
} else {
  document.getElementById("heading").textContent = "This share link is incomplete.";
  document.getElementById("status").textContent = "Ask the sender to copy the link again from YTMD Lite.";
}
