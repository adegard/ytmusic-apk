# YT Music (Web / PWA)

A browser app that searches YouTube and streams songs on-device. Runs on iPhone
(Safari) and Android, installable to the home screen — no App Store, no Mac, no
signing.

- **Search** — the results page is parsed for `ytInitialData`.
- **Streams** — the InnerTube `/player` API (anonymous `ANDROID` client) returns
  already-signed googlevideo URLs; the best AAC (`audio/mp4`) stream is picked
  (iOS Safari cannot play WebM/Opus).
- **Playback** — an `<audio>` element with the Media Session API for
  lock-screen / Control-Center controls and background audio.

## Why a proxy

YouTube's endpoints send no CORS headers, so a browser page cannot call them
directly. All requests go through a tiny free Cloudflare Worker that just
forwards them and adds CORS headers.

## Setup

### 1. Deploy the proxy (one time, free)

1. Sign up for a free account at [cloudflare.com](https://dash.cloudflare.com/sign-up).
2. Dashboard → **Workers & Pages** → **Create** → **Create Worker**.
3. Replace the code with the contents of [`workers/ytproxy.js`](../workers/ytproxy.js)
   → **Deploy**.
4. Note your worker URL, e.g. `https://ytmusic-proxy.yourname.workers.dev`.

### 2. Point the app at it

Edit `web/app.js` and set:

```js
const PROXY = "https://ytmusic-proxy.yourname.workers.dev/";
```

### 3. Host the app

GitHub Pages is included in this repo (`.github/workflows/deploy-pwa.yml` deploys
the `web/` folder automatically):

1. Repo → **Settings → Pages** → **Source: GitHub Actions** → Save.
2. The workflow runs on every push that changes `web/`.
3. Open the resulting `https://adegard.github.io/ytmusic-apk/` URL.

### 4. Install on iPhone

Open the site in Safari → Share → **Add to Home Screen**. Background audio and
lock-screen controls work once the page is installed and playback is started
by a tap.

## Known limitations

- Anonymous streaming can be rate-limited or temporarily blocked by YouTube.
- Videos with no AAC audio track are skipped (WebM/Opus unsupported by Safari).
- The proxy is required unless the app is served somewhere without CORS
  restrictions; leaving `PROXY` empty is only useful for local testing.
