# YT Music (Web / PWA)

A browser app that searches YouTube and plays songs on-device. Runs on iPhone
(Safari) and Android, installable to the home screen — no App Store, no Mac, no
signing.

## How it works

- **Search** — a proxied GET of the YouTube results page, parsed for
  `ytInitialData`.
- **Playback** — a hidden YouTube embed iframe driven by the IFrame API.
  YouTube's own player handles stream URLs and signatures, so no stream
  endpoint is needed and background audio keeps working on iOS.
- **Lock screen** — the Media Session API shows now-playing info and
  play/pause controls in Control Center.

## Why a proxy

YouTube's endpoints send no CORS headers, so a browser page cannot call them
directly. Search goes through a tiny free Cloudflare Worker that just forwards
the request and adds CORS headers. (The player API is blocked for the worker's
datacenter IP, which is why playback uses the embed player instead.)

## Setup

### 1. Deploy the proxy (one time, free)

1. Sign up for a free account at [cloudflare.com](https://dash.cloudflare.com/sign-up).
2. Dashboard → **Workers & Pages** → **Create** → **Create Worker**.
3. Replace the code with the contents of [`workers/ytproxy.js`](../workers/ytproxy.js)
   → **Deploy**.
4. Enable **Workers.dev** for the worker (Settings → Domains & Routes).

### 2. Point the app at it

Edit `web/app.js` and set:

```js
const PROXY = "https://ytmusic-proxy.yourname.workers.dev/";
```

### 3. Host the app

Clone this repo.
GitHub Pages is included in this repo (`.github/workflows/deploy-pwa.yml` deploys
the `web/` folder automatically):

1. Repo → **Settings → Pages** → **Source: GitHub Actions** → Save.
2. The workflow runs on every push that changes `web/`.


### 4. Install on iPhone

Open the site in Safari → Share → **Add to Home Screen**. Background audio and
lock-screen controls work once the page is installed and playback is started
by a tap.

## Known limitations

- Search can be rate-limited or temporarily blocked by YouTube.
- Videos that disallow embedding can't be played.
- The proxy is required unless the app is served somewhere without CORS
  restrictions; leaving `PROXY` empty is only useful for local testing.
