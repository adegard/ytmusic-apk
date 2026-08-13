"use strict";

// ---------------------------------------------------------------------------
// YT Music PWA
//
// Search + streaming logic runs entirely in your browser. YouTube does not
// send CORS headers, so both calls below go through a tiny Cloudflare Worker
// proxy (free, ~30 lines). Deploy it, then set PROXY to your worker URL.
//
//   workers/ytproxy.js  ->  https://<your-worker-subdomain>.workers.dev/
//
// If PROXY is left empty, the app still works when opened from a device that
// can make CORS-free requests (e.g. a local host) or you point it at any
// CORS proxy that accepts "?url=<encoded target>".
// ---------------------------------------------------------------------------

const PROXY = ""; // e.g. "https://ytmusic-proxy.example.workers.dev/"

const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0";
const PLAYER_ENDPOINT = "https://www.youtube.com/youtubei/v1/player?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8";

const audio = document.getElementById("audio");
const form = document.getElementById("search-form");
const input = document.getElementById("search-input");
const button = document.getElementById("search-button");
const statusEl = document.getElementById("status");
const resultsEl = document.getElementById("results");
const playerEl = document.getElementById("player");
const playerTitle = document.getElementById("player-title");
const playerSub = document.getElementById("player-sub");
const toggle = document.getElementById("toggle");
const iconPause = document.getElementById("icon-pause");
const iconPlay = document.getElementById("icon-play");

let current = null;

// --- proxy helpers ---------------------------------------------------------

function proxyUrl(target) {
  return PROXY + "?url=" + encodeURIComponent(target);
}

async function proxiedGet(target) {
  const res = await fetch(proxyUrl(target), { headers: { "User-Agent": UA } });
  if (!res.ok) throw new Error("Proxy returned HTTP " + res.status);
  return res.text();
}

async function proxiedPost(target, body) {
  const res = await fetch(proxyUrl(target), {
    method: "POST",
    headers: { "Content-Type": "application/json", "User-Agent": UA },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error("Proxy returned HTTP " + res.status);
  return res.json();
}

// --- search ----------------------------------------------------------------

async function doSearch(query) {
  const target =
    "https://www.youtube.com/results?search_query=" +
    encodeURIComponent(query) +
    "&hl=en";
  const html = await proxiedGet(target);
  const results = YT.parseSearch(html);
  if (!results.length) {
    throw new Error("No results — YouTube may have blocked this request. Try again shortly.");
  }
  return results;
}

async function resolveStream(videoId) {
  const player = await proxiedPost(PLAYER_ENDPOINT, {
    context: {
      client: { clientName: "ANDROID", clientVersion: "20.20.35", androidSdkVersion: 34 },
    },
    videoId: videoId,
  });
  const stream = YT.pickStream(player);
  if (!stream) {
    const status = (player && player.playabilityStatus) || {};
    const reason = status.reason || status.status || "unknown";
    throw new Error("Video unavailable: " + reason);
  }
  return stream;
}

// --- rendering -------------------------------------------------------------

function showStatus(message) {
  statusEl.textContent = message;
  statusEl.hidden = false;
}

function hideStatus() {
  statusEl.hidden = true;
}

function renderResults(results) {
  resultsEl.textContent = "";
  if (!results.length) {
    const p = document.createElement("div");
    p.className = "empty";
    p.textContent = "No results.";
    resultsEl.appendChild(p);
    return;
  }
  for (const r of results) {
    const item = document.createElement("button");
    item.className = "result";
    item.type = "button";

    const img = document.createElement("img");
    img.loading = "lazy";
    img.alt = "";
    img.src = "https://i.ytimg.com/vi/" + r.id + "/hqdefault.jpg";
    item.appendChild(img);

    const meta = document.createElement("div");
    meta.className = "meta";
    const title = document.createElement("div");
    title.className = "title";
    title.textContent = r.title;
    const channel = document.createElement("div");
    channel.className = "channel";
    channel.textContent = r.channel;
    meta.appendChild(title);
    meta.appendChild(channel);
    item.appendChild(meta);

    const dur = document.createElement("div");
    dur.className = "duration";
    dur.textContent = r.duration;
    item.appendChild(dur);

    item.addEventListener("click", function () {
      play(r);
    });
    resultsEl.appendChild(item);
  }
}

// --- playback --------------------------------------------------------------

async function play(result) {
  hideStatus();
  playerTitle.textContent = result.title;
  playerSub.textContent = "Loading…";
  playerEl.hidden = false;
  setIcon("play");
  current = result;
  try {
    const stream = await resolveStream(result.id);
    setMediaSession(stream, result);
    playerSub.textContent = stream.author;
    audio.src = stream.url;
    await audio.play();
    setIcon("pause");
  } catch (err) {
    showStatus(err.message || String(err));
    setIcon("play");
  }
}

function togglePlayPause() {
  if (audio.paused) {
    audio.play();
    setIcon("pause");
  } else {
    audio.pause();
    setIcon("play");
  }
}

function setIcon(which) {
  iconPlay.style.display = which === "play" ? "block" : "none";
  iconPause.style.display = which === "pause" ? "block" : "none";
}

function setMediaSession(stream, result) {
  if (!("mediaSession" in navigator)) return;
  navigator.mediaSession.metadata = new MediaMetadata({
    title: stream.title || result.title,
    artist: stream.author || result.channel,
    album: "YT Music",
    artwork: [
      { src: "https://i.ytimg.com/vi/" + result.id + "/hqdefault.jpg", sizes: "480x360", type: "image/jpeg" },
    ],
  });
  const actions = {
    play: function () { audio.play(); },
    pause: function () { audio.pause(); },
    toggleplaypause: togglePlayPause,
  };
  for (const key in actions) {
    try {
      navigator.mediaSession.setActionHandler(key, actions[key]);
    } catch (e) { /* unsupported action */ }
  }
  if (stream.duration) {
    try {
      navigator.mediaSession.setPositionState({
        duration: stream.duration,
        playbackRate: 1.0,
        position: 0,
      });
    } catch (e) { /* unsupported */ }
  }
}

// --- wiring ----------------------------------------------------------------

audio.addEventListener("pause", function () { setIcon("play"); });
audio.addEventListener("play", function () { setIcon("pause"); });
audio.addEventListener("error", function () {
  if (current) showStatus("Playback failed. Try another video or wait and retry.");
  setIcon("play");
});

form.addEventListener("submit", async function (e) {
  e.preventDefault();
  const q = input.value.trim();
  if (!q) return;
  button.disabled = true;
  hideStatus();
  resultsEl.textContent = "";
  const p = document.createElement("div");
  p.className = "empty";
  p.textContent = "Searching…";
  resultsEl.appendChild(p);
  try {
    renderResults(await doSearch(q));
  } catch (err) {
    resultsEl.textContent = "";
    showStatus(err.message || String(err));
  } finally {
    button.disabled = false;
  }
});

toggle.addEventListener("click", togglePlayPause);

if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("sw.js").catch(function () {});
}
