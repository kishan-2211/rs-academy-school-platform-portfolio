const OFFLINE_PAGE = `<!doctype html><html lang="en" data-theme="light"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="theme-color" content="#f7f3ea"><script>(()=>{let p='system';try{const v=localStorage.getItem('educore-theme');if(v==='light'||v==='dark'||v==='system')p=v}catch{}const d=matchMedia('(prefers-color-scheme: dark)').matches,r=p==='system'?(d?'dark':'light'):p;document.documentElement.dataset.theme=r;document.querySelector('meta[name=theme-color]').content=r==='dark'?'#17130f':'#f7f3ea'})()<\/script><title>R.S. Academy — Offline</title><style>:root{color-scheme:light;--bg:#f7f3ea;--card:#fff;--text:#173b34;--muted:#536861;--border:#cfded7;--shadow:#173b3414}:root[data-theme=dark]{color-scheme:dark;--bg:#17130f;--card:#282119;--text:#f5ede2;--muted:#bdafa1;--border:#51463b;--shadow:#0006}body{margin:0;min-height:100vh;display:grid;place-items:center;background:var(--bg);color:var(--text);font:16px/1.6 system-ui,sans-serif}.card{width:min(88vw,520px);padding:32px;border:1px solid var(--border);border-radius:20px;background:var(--card);box-shadow:0 20px 60px var(--shadow)}.eyebrow{color:#72ae96;font-size:12px;font-weight:800;letter-spacing:.1em;text-transform:uppercase}h1{margin:.45rem 0;font:600 2rem Georgia,serif}p{margin:.4rem 0;color:var(--muted)}</style></head><body><main class="card"><span class="eyebrow">R.S. Academy</span><h1>You are offline</h1><p>Previously viewed private school data is not stored on this device. Reconnect to open the secure platform and receive the latest verified updates.</p></main></body></html>`;

self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (event) => event.waitUntil(self.clients.claim()));

self.addEventListener("fetch", (event) => {
  if (event.request.mode !== "navigate") return;
  event.respondWith(fetch(event.request).catch(() => new Response(OFFLINE_PAGE, {
    status: 503,
    headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" },
  })));
});

self.addEventListener("push", (event) => {
  let payload = {};
  try {
    payload = event.data ? event.data.json() : {};
  } catch {
    payload = {};
  }
  const title = typeof payload.title === "string" && payload.title.trim()
    ? payload.title.slice(0, 180)
    : "R.S. Academy update";
  const body = typeof payload.body === "string" && payload.body.trim()
    ? payload.body.slice(0, 4000)
    : "Open the verified school inbox for details.";
  let target = "/portal";
  if (typeof payload.url === "string") {
    try {
      const candidate = new URL(payload.url, self.location.origin);
      if (candidate.origin === self.location.origin) target = `${candidate.pathname}${candidate.search}${candidate.hash}`;
    } catch {
      target = "/portal";
    }
  }
  event.waitUntil(self.registration.showNotification(title, {
    body,
    icon: "/brand/rs-academy-logo.jpeg",
    badge: "/brand/rs-academy-logo.jpeg",
    tag: typeof payload.tag === "string" ? payload.tag.slice(0, 160) : undefined,
    renotify: Boolean(payload.renotify),
    data: { url: target },
  }));
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const requested = event.notification.data?.url || "/portal";
  event.waitUntil(self.clients.matchAll({ type: "window", includeUncontrolled: true }).then((clients) => {
    for (const client of clients) {
      if (new URL(client.url).origin === self.location.origin && "focus" in client) {
        if ("navigate" in client) void client.navigate(requested);
        return client.focus();
      }
    }
    return self.clients.openWindow(requested);
  }));
});
