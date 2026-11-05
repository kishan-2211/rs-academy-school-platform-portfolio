# Domains and browser origins

The example production origin is `https://rsacademyschool.in`. It describes a same-origin deployment contract, not proof that the site is deployed or that the publisher controls a live service.

| Address | Purpose |
| --- | --- |
| `https://rsacademyschool.in` | Public website and all browser portals |
| `https://www.rsacademyschool.in` | Redirect-only alias |
| `https://rsacademyschool.in/api/v1` | Same-origin API path |
| `https://api.rsacademyschool.in/api/v1` | Optional separate API address requiring its own deployment review |

`NEXT_PUBLIC_APP_URL`, `NEXT_PUBLIC_API_URL`, `APP_PUBLIC_URL` and `APP_ALLOWED_ORIGINS` use the canonical HTTPS origin. The web app appends `/api/v1` itself, so `NEXT_PUBLIC_API_URL` must not contain that suffix.

The Next.js configuration does **not** implement API rewrites. Configure the edge proxy to route `/api/v1/` to the private API **without stripping its path**. Preserve cookies, CSRF, idempotency and request identifiers. Restrict forwarded headers to the trusted proxy.

Set `APP_COOKIE_SECURE=true` and leave `APP_COOKIE_DOMAIN` empty to keep cookies host-only. Do not add wildcard origins or the redirect-only alias to authenticated browser origins. Avoid caching authenticated pages, API responses and private documents.

Build-time public URLs must match the eventual deployment. Supplying new environment values only after a web build does not rewrite the browser bundle.

Before a live deployment, verify domain ownership, DNS, HTTPS, redirects, origin handling, session/MFA behavior and provider return/callback addresses. None of those checks is established by the example environment file.
