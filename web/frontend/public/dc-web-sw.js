/* DeskCubby Web — minimal service worker: app shell cache-first, API/network-only. */
const CACHE = "deskcubby-web-v1";
const SHELL = ["/", "/index.html", "/manifest.webmanifest"];

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);
  if (event.request.method !== "GET" || url.pathname.startsWith("/api")) return;
  if (url.origin !== self.location.origin) return;
  event.respondWith(
    caches.match(event.request).then(
      (hit) =>
        hit ||
        fetch(event.request)
          .then((resp) => {
            if (resp.ok && !url.pathname.startsWith("/icons")) {
              const copy = resp.clone();
              caches.open(CACHE).then((c) => c.put(event.request, copy));
            }
            return resp;
          })
          .catch(() => caches.match("/"))
    )
  );
});
