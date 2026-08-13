"use strict";

// ---------------------------------------------------------------------------
// YT Music PWA
//
// - Search:  proxied GET of the YouTube results page, parsed for ytInitialData.
//            YouTube sends no CORS headers, so this goes through the Cloudflare
//            Worker proxy (workers/ytproxy.js). The worker IP is allowed for
//            search but YouTube blocks it for the player API.
// - Playback: a hidden YouTube embed iframe + IFrame API. No stream URL, no
//            CORS, no proxy needed — YouTube's own player handles signatures.
//            The iframe is kept 1px and on-screen (not opacity:0) so mobile
//            browsers still consider it visible and allow autoplay.
// ---------------------------------------------------------------------------

const PROXY = "https://ytmusic-proxy.degardinarnaud.workers.dev/";

const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0";

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
let ytPlayer = null;
let pendingVideoId = null;
let startCheckTimer = null;

// --- proxy helper (search only) -------------------------------------------

async function doSearch(query) {
  const target =
    "https://www.youtube.com/results?search_query=" +
    encodeURIComponent(query) +
    "&hl=en";
  const res = await fetch(PROXY + "?url=" + encodeURIComponent(target), {
    headers: { "User-Agent": UA },
  });
  if (!res.ok) throw new Error("Proxy returned HTTP " + res.status);
  const html = await res.text();
  const results = YT.parseSearch(html);
  if (!results.length) {
    throw new Error("No results — YouTube may have blocked this request. Try again shortly.");
  }
  return results;
}

// --- YouTube IFrame API ----------------------------------------------------

function loadPlayerAPI() {
  if (window.YT && window.YT.Player) {
    createPlayer();
    return;
  }
  if (document.getElementById("yt-iframe-api")) return;
  const tag = document.createElement("script");
  tag.id = "yt-iframe-api";
  tag.src = "https://www.youtube.com/iframe_api";
  document.head.appendChild(tag);
}

window.onYouTubeIframeAPIReady = createPlayer;

function createPlayer() {
  if (ytPlayer) return;
  ytPlayer = new YT.Player("yt-embed", {
    height: "1",
    width: "1",
    playerVars: {
      autoplay: 0,
      playsinline: 1,
      rel: 0,
      controls: 0,
      fs: 0,
      disablekb: 1,
      iv_load_policy: 3,
    },
    events: {
      onReady: onPlayerReady,
      onStateChange: onPlayerState,
      onError: onPlayerError,
    },
  });
}

function onPlayerReady() {
  if (pendingVideoId) {
    const id = pendingVideoId;
    pendingVideoId = null;
    ytPlayer.loadVideoById(id);
    ytPlayer.playVideo();
    scheduleStartCheck();
  }
}

function onPlayerState(event) {
  if (!current) return;
  if (event.data === YT.PlayerState.PLAYING) {
    setIcon("pause");
    playerSub.textContent = current.channel;
    updatePositionState();
  } else if (
    event.data === YT.PlayerState.PAUSED ||
    event.data === YT.PlayerState.ENDED
  ) {
    setIcon("play");
  }
}

function onPlayerError() {
  if (!current) return;
  setIcon("play");
  showStatus("This video can't be played here (maybe embedding is disabled).");
}

function updatePositionState() {
  if (!navigator.mediaSession || !ytPlayer || !ytPlayer.getDuration) return;
  try {
    navigator.mediaSession.setPositionState({
      duration: ytPlayer.getDuration() || 0,
      playbackRate: 1.0,
      position: ytPlayer.getCurrentTime ? ytPlayer.getCurrentTime() : 0,
    });
  } catch (e) { /* unsupported */ }
}

// If autoplay was blocked by the browser, prompt the user to tap play.
function scheduleStartCheck() {
  clearTimeout(startCheckTimer);
  startCheckTimer = setTimeout(function () {
    if (!current || !ytPlayer || !ytPlayer.getPlayerState) return;
    const state = ytPlayer.getPlayerState();
    if (state === YT.PlayerState.PLAYING || state === YT.PlayerState.BUFFERING) return;
    playerSub.textContent = "Tap play to start";
    setIcon("play");
  }, 2500);
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
      play(result);
    });
    resultsEl.appendChild(item);
  }
}

// --- playback --------------------------------------------------------------

function play(result) {
  hideStatus();
  current = result;
  playerTitle.textContent = result.title;
  playerSub.textContent = "Loading…";
  playerEl.hidden = false;
  setIcon("play");
  setMediaSession(result);

  if (ytPlayer && ytPlayer.loadVideoById) {
    ytPlayer.loadVideoById(result.id);
    ytPlayer.playVideo();
    scheduleStartCheck();
  } else {
    pendingVideoId = result.id;
  }
}

function togglePlayPause() {
  if (!ytPlayer || !ytPlayer.getPlayerState) return;
  if (ytPlayer.getPlayerState() === YT.PlayerState.PLAYING) {
    ytPlayer.pauseVideo();
  } else {
    ytPlayer.playVideo();
  }
}

function setIcon(which) {
  iconPlay.style.display = which === "play" ? "block" : "none";
  iconPause.style.display = which === "pause" ? "block" : "none";
}

function setMediaSession(result) {
  if (!("mediaSession" in navigator)) return;
  navigator.mediaSession.metadata = new MediaMetadata({
    title: result.title,
    artist: result.channel,
    album: "YT Music",
    artwork: [
      { src: "https://i.ytimg.com/vi/" + result.id + "/hqdefault.jpg", sizes: "480x360", type: "image/jpeg" },
    ],
  });
  const actions = {
    play: function () { ytPlayer && ytPlayer.playVideo && ytPlayer.playVideo(); },
    pause: function () { ytPlayer && ytPlayer.pauseVideo && ytPlayer.pauseVideo(); },
    toggleplaypause: togglePlayPause,
  };
  for (const key in actions) {
    try {
      navigator.mediaSession.setActionHandler(key, actions[key]);
    } catch (e) { /* unsupported action */ }
  }
}

// --- wiring ----------------------------------------------------------------

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

loadPlayerAPI();

if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("sw.js").catch(function () {});
}
