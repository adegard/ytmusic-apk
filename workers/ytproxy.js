// YT Music CORS proxy — deploy to Cloudflare Workers (free).
//
//   workers.dev -> https://<your-worker>.workers.dev/
//
// The PWA (web/) calls this as ?url=<encoded target>. YouTube's endpoints do
// not send CORS headers, so browsers cannot call them directly. This tiny
// worker just forwards GET/POST to youtube.com and adds CORS headers.
//
// Routes:
//   GET/POST  ?url=<encoded youtube URL>   -> CORS-forwarded response
//   POST      /next  {"videoId":"..."}     -> { suggestions: [{id,title,channel}] }
//        The InnerTube /next endpoint (watch-page suggestions) is bot-guarded
//        but answers the ANDROID client. Its response is ~12MB with a nested
//        element layout, so we parse it here and return only the compact list.
//        (The /player endpoint stays blocked on datacenter IPs — that's why
//        playback uses the embed iframe instead.)
//
// Deploy (classic/service-worker format):
//   curl -X PUT "https://api.cloudflare.com/client/v4/accounts/<ACCOUNT>/workers/scripts/<NAME>" \
//     -H "Authorization: Bearer $CF_API_TOKEN" \
//     -F 'metadata={"body_part":"script","main_module":""};type=application/json' \
//     -F 'script=@workers/ytproxy.js;type=application/javascript'
//   Then set PROXY in web/app.js to your worker URL.

const DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0";

const CORS = {
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "GET, POST, OPTIONS",
  "access-control-allow-headers": "content-type, user-agent",
  "access-control-expose-headers": "*",
};

addEventListener("fetch", (event) => {
  const request = event.request;
  const url = new URL(request.url);

  // Browser preflight for the POST requests.
  if (request.method === "OPTIONS") {
    event.respondWith(new Response(null, { status: 204, headers: CORS }));
    return;
  }

  // POST /next  {"videoId": "..."} -> suggestions
  if (url.pathname === "/next" && request.method === "POST") {
    event.respondWith(handleNext(request));
    return;
  }

  const target = url.searchParams.get("url");
  if (!target) {
    event.respondWith(new Response("Missing ?url= parameter", { status: 400, headers: CORS }));
    return;
  }

  let targetUrl;
  try {
    targetUrl = new URL(target);
  } catch (err) {
    event.respondWith(new Response("Invalid target URL", { status: 400, headers: CORS }));
    return;
  }

  if (!["www.youtube.com", "youtube.com", "music.youtube.com"].includes(targetUrl.hostname)) {
    event.respondWith(new Response("Only youtube.com targets are allowed", { status: 403, headers: CORS }));
    return;
  }

  event.respondWith(proxy(targetUrl, request));
});

// --- generic pass-through proxy --------------------------------------------

async function proxy(targetUrl, request) {
  const headers = new Headers({
    "User-Agent": DESKTOP_UA,
    "Accept-Language": "en",
  });

  let body;
  if (request.method === "GET" || request.method === "HEAD") {
    body = undefined;
  } else {
    headers.set("Content-Type", "application/json");
    body = await request.arrayBuffer();
  }

  const upstream = await fetch(targetUrl, { method: request.method, headers, body });
  const response = new Response(upstream.body, {
    status: upstream.status,
    statusText: upstream.statusText,
    headers: upstream.headers,
  });
  for (const [key, value] of Object.entries(CORS)) {
    response.headers.set(key, value);
  }
  return response;
}

// --- /next suggestions ------------------------------------------------------

async function handleNext(request) {
  let payload;
  try {
    payload = await request.json();
  } catch (err) {
    return new Response("Invalid JSON body", { status: 400, headers: CORS });
  }
  const videoId = payload && payload.videoId;
  if (typeof videoId !== "string" || !/^[\w-]{11}$/.test(videoId)) {
    return new Response("Invalid videoId", { status: 400, headers: CORS });
  }
  const suggestions = await nextSuggestions(videoId);
  return new Response(JSON.stringify({ suggestions }), {
    status: 200,
    headers: { ...CORS, "content-type": "application/json" },
  });
}

async function nextSuggestions(videoId) {
  const innertube = "https://www.youtube.com/youtubei/v1/next";
  const payload = {
    context: {
      client: { clientName: "ANDROID", clientVersion: "20.20.35", androidSdkVersion: 34, hl: "en" },
    },
    videoId: videoId,
  };

  let upstream;
  try {
    upstream = await fetch(innertube, {
      method: "POST",
      headers: { "Content-Type": "application/json", "User-Agent": DESKTOP_UA, "Accept-Language": "en" },
      body: JSON.stringify(payload),
    });
  } catch (err) {
    return [];
  }
  if (!upstream.ok) return [];

  let data;
  try {
    data = await upstream.json();
  } catch (err) {
    return [];
  }

  const out = [];
  try {
    const results =
      data.contents &&
      data.contents.singleColumnWatchNextResults &&
      data.contents.singleColumnWatchNextResults.results &&
      data.contents.singleColumnWatchNextResults.results.results;
    if (!results || !Array.isArray(results.contents)) return out;

    for (const section of results.contents) {
      const isr = section && section.itemSectionRenderer;
      if (!isr || !Array.isArray(isr.contents)) continue;

      for (const item of isr.contents) {
        const model =
          item &&
          item.elementRenderer &&
          item.elementRenderer.newElement &&
          item.elementRenderer.newElement.type &&
          item.elementRenderer.newElement.type.componentType &&
          item.elementRenderer.newElement.type.componentType.model;
        const vw = model && model.videoWithContextModel && model.videoWithContextModel.videoWithContextData;
        if (!vw) continue;

        const lmv = vw.videoData && vw.videoData.lockupMetadata && vw.videoData.lockupMetadata.lockupMetadataViewModel;
        if (!lmv) continue;

        const title = lmv.title && typeof lmv.title.content === "string" ? lmv.title.content : "";
        if (!title) continue;

        const id = findWatchId(vw.onTap);
        if (!id) continue;

        let channel = "";
        let live = false;
        try {
          const rows =
            (lmv.metadata && lmv.metadata.contentMetadataViewModel && lmv.metadata.contentMetadataViewModel.metadataRows) || [];
          const metaTexts = [];
          for (const row of rows) {
            for (const part of row.metadataParts || []) {
              const text = part && part.text && part.text.content;
              if (typeof text === "string") metaTexts.push(text);
            }
          }
          for (const t of metaTexts) {
            if (!channel && t.indexOf("\u00B7") >= 0) {
              channel = t.split("\u00B7")[0].trim();
            }
            if (/streamed|live/i.test(t)) live = true;
          }
        } catch (err) { /* optional field */ }

        out.push({ id: id, title: title, channel: channel, live: live });
        if (out.length >= 20) return out;
      }
    }
  } catch (err) { /* malformed payload */ }

  return out;
}

// Depth-first search for the first watchEndpoint.videoId.
function findWatchId(node) {
  if (!node || typeof node !== "object") return null;
  if (node.watchEndpoint && typeof node.watchEndpoint.videoId === "string") return node.watchEndpoint.videoId;
  for (const key in node) {
    const found = findWatchId(node[key]);
    if (found) return found;
  }
  return null;
}
