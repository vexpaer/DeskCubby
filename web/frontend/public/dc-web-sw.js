/* DeskCubby Web — fresh navigations + immutable-asset offline cache. */
const CACHE = "deskcubby-web-v0.23.5-r4";
const SHELL = [
  "/",
  "/index.html",
  "/manifest.webmanifest",
  "/icons/icon-192.png",
  "/icons/icon-512.png",
  "/icons/icon-512-maskable.png",
];

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
  const navigation = event.request.mode === "navigate" || url.pathname === "/" || url.pathname === "/index.html";
  if (navigation) {
    event.respondWith(
      fetch(event.request)
        .then((resp) => {
          if (resp.ok) caches.open(CACHE).then((c) => c.put("/index.html", resp.clone()));
          return resp;
        })
        .catch(() => caches.match("/index.html").then((hit) => hit || caches.match("/")))
    );
    return;
  }
  event.respondWith(
    caches.match(event.request).then((hit) => hit || fetch(event.request).then((resp) => {
      if (resp.ok) caches.open(CACHE).then((c) => c.put(event.request, resp.clone()));
      return resp;
    }))
  );
});
