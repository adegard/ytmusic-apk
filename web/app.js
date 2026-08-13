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

const APP_VERSION = "1.3.2";

const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0";

const form = document.getElementById("search-form");
const input = document.getElementById("search-input");
const button = document.getElementById("search-button");
const statusEl = document.getElementById("status");
const resultsEl = document.getElementById("results");
const playerEl = document.getElementById("player");
const playerTitle = document.getElementById("player-title");
const playerSub = document.getElementById("player-sub");
const playerNextEl = document.getElementById("player-next");
const toggle = document.getElementById("toggle");
const iconPause = document.getElementById("icon-pause");
const iconPlay = document.getElementById("icon-play");
const versionEl = document.getElementById("version");
const favOpen = document.getElementById("fav-open");
const favClose = document.getElementById("fav-close");
const favBackdrop = document.getElementById("fav-backdrop");
const favModal = document.getElementById("fav-modal");
const favListEl = document.getElementById("fav-list");
const favToggle = document.getElementById("fav-toggle");
const heartOutline = document.getElementById("heart-outline");
const heartFilled = document.getElementById("heart-filled");

favModal.hidden = true;
playerEl.hidden = true;
statusEl.hidden = true;

let current = null;
let ytPlayer = null;
let pendingVideoId = null;
let startCheckTimer = null;
let queue = [];
let adMuted = false;
let adCheckTimer = null;
let stallCount = 0;
let favorites = [];
const playedIds = new Set();

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
      mute: 1,
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
  startAdCheck();
}

// --- ad handling -----------------------------------------------------------
// Videos that START muted don't get pre-roll ads, and muted autoplay is always
// allowed by the browser (no tap gesture needed) — so every song is started
// muted and unmuted once PLAYING fires. This avoids pre-rolls entirely and
// makes the auto-queue work even without a fresh tap.
//
// Mid-roll ads can still appear later. The IFrame API gives no ad events, but
// during an ad getVideoData() returns { video_id: "", title: "Advertisement" }.
// Poll for that and mute; unmute when real content plays again.
function startAdCheck() {
  if (adCheckTimer) return;
  adCheckTimer = setInterval(checkAd, 500);
}

function stopAdCheck() {
  if (adCheckTimer) {
    clearInterval(adCheckTimer);
    adCheckTimer = null;
  }
}

function checkAd() {
  if (!ytPlayer || !ytPlayer.getVideoData) return;
  let vd = null;
  try {
    vd = ytPlayer.getVideoData();
  } catch (e) { return; }
  const isAd = !!vd &&
    vd.video_id === "" &&
    (vd.title === "Advertisement" || vd.title === "Video advertising");

  if (isAd) {
    if (!adMuted) {
      adMuted = true;
      try { ytPlayer.mute(); } catch (e) { /* ignore */ }
      playerSub.textContent = "Ad playing (muted)…";
    }
  } else if (adMuted) {
    adMuted = false;
    try { ytPlayer.unMute(); } catch (e) { /* ignore */ }
    if (current) playerSub.textContent = current.channel;
  }
}

function onPlayerReady() {
  if (pendingVideoId) {
    pendingVideoId = null;
    if (current) startSong(current);
  }
}

function onPlayerState(event) {
  if (!current) return;
  if (event.data === YT.PlayerState.PLAYING) {
    stallCount = 0;
    setIcon("pause");
    if (adMuted) {
      playerSub.textContent = "Ad playing (muted)…";
    } else {
      playerSub.textContent = current.channel;
      try { ytPlayer.unMute(); } catch (e) { /* ignore */ }
    }
    updatePositionState();
  } else if (
    event.data === YT.PlayerState.PAUSED ||
    event.data === YT.PlayerState.ENDED
  ) {
    setIcon("play");
    if (event.data === YT.PlayerState.ENDED) playNext();
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

// Stall watchdog: a stuck BUFFERING/UNSTARTED embed (often a failed pre-roll ad)
// otherwise shows "Loading…" forever. Nudge playback, escalate to a fresh
// loadVideoById (that is what makes a manual retap work), then surface a retry
// message only after several recovery attempts have failed.
function scheduleStartCheck() {
  clearTimeout(startCheckTimer);
  startCheckTimer = setTimeout(checkStalled, 6000);
}

function checkStalled() {
  if (!current || !ytPlayer || !ytPlayer.getPlayerState) return;
  let state = -1;
  try { state = ytPlayer.getPlayerState(); } catch (e) { /* ignore */ }

  if (state === YT.PlayerState.PLAYING) {
    stallCount = 0;
    return;
  }
  if (state === YT.PlayerState.PAUSED || state === YT.PlayerState.ENDED) {
    stallCount = 0;
    playerSub.textContent = "Tap play to start";
    setIcon("play");
    return;
  }

  // During an ad the player often reports BUFFERING while the (muted) ad plays.
  let vd = null;
  try { vd = ytPlayer.getVideoData(); } catch (e) { /* ignore */ }
  if (vd && vd.video_id === "" && (vd.title === "Advertisement" || vd.title === "Video advertising")) {
    return;
  }

  stallCount++;
  if (stallCount >= 5) {
    stallCount = 0;
    playerSub.textContent = "Stalled — tap play to retry";
    setIcon("play");
    showStatus("Playback stalled (state " + state + "). Press play or tap the song again.");
    return;
  }

  try {
    if (stallCount >= 2) {
      ytPlayer.loadVideoById(current.id);
    }
    ytPlayer.playVideo();
  } catch (e) { /* ignore */ }
  playerSub.textContent = "Still loading…";
  scheduleStartCheck();
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

// Manual selection: reset the suggestion queue and start fresh.
function play(result) {
  queue.length = 0;
  playerNextEl.textContent = "";
  startSong(result);
}

function startSong(result) {
  hideStatus();
  current = result;
  playedIds.add(result.id);
  if (playedIds.size > 100) playedIds.clear();
  stallCount = 0;
  playerTitle.textContent = result.title;
  playerSub.textContent = "Loading…";
  playerNextEl.textContent = "";
  playerEl.hidden = false;
  setIcon("play");
  setMediaSession(result);
  updateFavHeart();

  if (ytPlayer && ytPlayer.loadVideoById) {
    try { ytPlayer.mute(); } catch (e) { /* ignore */ }
    ytPlayer.loadVideoById(result.id);
    ytPlayer.playVideo();
    scheduleStartCheck();
  } else {
    pendingVideoId = result.id;
  }
  fetchSuggestions(result.id);
}

function playNext() {
  while (queue.length && (playedIds.has(queue[0].id) || (current && queue[0].id === current.id))) {
    queue.shift();
  }
  if (!queue.length) {
    showStatus("End of playlist.");
    return;
  }
  const next = queue.shift();
  startSong(next);
}

// Load the "Up next" / related list for the current video and append it to the
// queue. The worker fetches the InnerTube /next response server-side (it is
// ~12MB and bot-guarded) and returns only the compact suggestion list.
// Guarded so a slow response can't pollute a newer song's queue.
async function fetchSuggestions(videoId) {
  try {
    const res = await fetch(PROXY + "next", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ videoId: videoId }),
    });
    if (!res.ok) return;
    const data = await res.json();
    const suggestions = data.suggestions || [];
    if (!suggestions.length) return;
    if (!current || current.id !== videoId) return;
    // Skip YouTube-promoted mix/live radio items when regular songs exist;
    // fall back to live items only if everything is live.
    const regular = suggestions.filter(function (s) { return !s.live; });
    const pool = regular.length ? regular : suggestions.filter(function (s) { return s.live; });
    const fresh = pool.filter(function (s) {
      return !playedIds.has(s.id) && !queue.some(function (q) { return q.id === s.id; });
    });
    if (!fresh.length) return;
    queue = queue.concat(fresh).slice(0, 10);
    if (current && current.id === videoId && queue.length) {
      playerNextEl.textContent = "Up next: " + queue[0].title;
    }
  } catch (e) { /* non-fatal */ }
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

// --- favorites -------------------------------------------------------------

function loadFavorites() {
  try {
    favorites = JSON.parse(localStorage.getItem("ytmusic:favorites") || "[]") || [];
  } catch (e) {
    favorites = [];
  }
}

function saveFavorites() {
  try {
    localStorage.setItem("ytmusic:favorites", JSON.stringify(favorites));
  } catch (e) { /* storage full/unavailable */ }
}

function isFavorite(id) {
  return favorites.some(function (f) { return f.id === id; });
}

function updateFavHeart() {
  const has = current && isFavorite(current.id);
  heartOutline.style.display = has ? "none" : "block";
  heartFilled.style.display = has ? "block" : "none";
  favToggle.classList.toggle("favorited", !!has);
  favToggle.disabled = !current;
}

function toggleFavorite() {
  if (!current) return;
  const idx = favorites.findIndex(function (f) { return f.id === current.id; });
  if (idx >= 0) {
    favorites.splice(idx, 1);
  } else {
    favorites.unshift({
      id: current.id,
      title: current.title,
      channel: current.channel || "",
    });
  }
  saveFavorites();
  updateFavHeart();
  if (!favModal.hidden) renderFavorites();
}

function renderFavorites() {
  favListEl.textContent = "";
  if (!favorites.length) {
    const p = document.createElement("div");
    p.className = "fav-empty";
    p.textContent = "No favorites yet — tap the heart on a song.";
    favListEl.appendChild(p);
    return;
  }
  for (const f of favorites) {
    const row = document.createElement("div");
    row.className = "fav-item";

    const info = document.createElement("button");
    info.type = "button";
    info.className = "fav-play";
    const t = document.createElement("div");
    t.className = "fav-title";
    t.textContent = f.title;
    const c = document.createElement("div");
    c.className = "fav-channel";
    c.textContent = f.channel || "";
    info.appendChild(t);
    info.appendChild(c);
    info.addEventListener("click", function () {
      closeFavorites();
      play({ id: f.id, title: f.title, channel: f.channel });
    });

    const del = document.createElement("button");
    del.type = "button";
    del.className = "fav-del";
    del.setAttribute("aria-label", "Remove from favorites");
    del.textContent = "✕";
    del.addEventListener("click", function () {
      const i = favorites.findIndex(function (x) { return x.id === f.id; });
      if (i >= 0) {
        favorites.splice(i, 1);
        saveFavorites();
        renderFavorites();
        updateFavHeart();
      }
    });

    row.appendChild(info);
    row.appendChild(del);
    favListEl.appendChild(row);
  }
}

function openFavorites() {
  renderFavorites();
  favModal.hidden = false;
}

function closeFavorites() {
  favModal.hidden = true;
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
favToggle.addEventListener("click", toggleFavorite);
favOpen.addEventListener("click", openFavorites);
favClose.addEventListener("click", closeFavorites);
favBackdrop.addEventListener("click", closeFavorites);

versionEl.textContent = "v" + APP_VERSION;
document.title = "YT Music v" + APP_VERSION;

loadFavorites();
updateFavHeart();

loadPlayerAPI();

if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("sw.js").catch(function () {});
}
