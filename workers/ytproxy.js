// YT Music CORS proxy — deploy to Cloudflare Workers (free).
//
//   workers.dev -> https://<your-worker>.workers.dev/
//
// The PWA (web/) calls this as ?url=<encoded target>. YouTube's endpoints do
// not send CORS headers, so browsers cannot call them directly. This tiny
// worker just forwards GET/POST to youtube.com and adds CORS headers.
//
// Deploy: Cloudflare dashboard -> Workers & Pages -> Create -> Worker ->
// paste this file -> Deploy. Then set PROXY in web/app.js to your worker URL.
//
// Classic (service worker) format on purpose — it deploys cleanly via the
// dashboard and the Workers API without module metadata.

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

  // Browser preflight for the POST request.
  if (request.method === "OPTIONS") {
    event.respondWith(new Response(null, { status: 204, headers: CORS }));
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
