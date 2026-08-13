"use strict";

const CACHE = "ytmusic-v3";
const SHELL = ["./", "./index.html", "./style.css", "./app.js", "./yt.js", "./icon.svg"];

self.addEventListener("install", function (event) {
  event.waitUntil(
    caches.open(CACHE).then(function (cache) {
      return cache.addAll(SHELL);
    }).then(function () {
      return self.skipWaiting();
    })
  );
});

self.addEventListener("activate", function (event) {
  event.waitUntil(
    caches.keys().then(function (keys) {
      return Promise.all(
        keys.filter(function (k) { return k !== CACHE; }).map(function (k) { return caches.delete(k); })
      );
    }).then(function () {
      return self.clients.claim();
    })
  );
});

self.addEventListener("fetch", function (event) {
  const url = new URL(event.request.url);
  const sameOrigin = url.origin === self.location.origin;
  const method = event.request.method;

  if (sameOrigin && method === "GET") {
    event.respondWith(
      caches.match(event.request).then(function (cached) {
        return cached || fetch(event.request).then(function (res) {
          if (res.ok) {
            const copy = res.clone();
            caches.open(CACHE).then(function (cache) { cache.put(event.request, copy); });
          }
          return res;
        });
      })
    );
    return;
  }

  // Cross-origin (proxy, media, thumbnails): never cache, network only.
  event.respondWith(fetch(event.request));
});
