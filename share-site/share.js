"use strict";

const songIds = new URLSearchParams(window.location.search).getAll("song");
const videoId = songIds.length === 1 ? songIds[0] : null;

if (videoId && /^[A-Za-z0-9_-]{11}$/.test(videoId)) {
  const playerUrl = `ytmd-lite://song/${videoId}`;
  document.getElementById("player").href = playerUrl;
  document.getElementById("music").href = `https://music.youtube.com/watch?v=${videoId}`;
  document.getElementById("youtube").href = `https://www.youtube.com/watch?v=${videoId}`;
  document.getElementById("destinations").hidden = false;
  document.getElementById("status").textContent = "Opening this song in YTMD Lite…";
  // Some browsers require a click before launching an installed app. Keep the
  // explicit app button and browser links available regardless of the outcome.
  try {
    window.location.assign(playerUrl);
  } catch {
    document.getElementById("status").textContent = "Choose where to listen to this shared song.";
  }
} else {
  document.getElementById("heading").textContent = "This song link is incomplete.";
  document.getElementById("status").textContent = "Ask the sender to copy the song link again from YTMD Lite.";
}
