# Register/login failure — root cause and fix

Your screenshot showed the actual failing request:
```
Access to XMLHttpRequest at 'https://confident-wonder-dev.up.railway.app/auth/register'
from origin 'https://finora-cng.pages.dev' has been blocked by CORS policy...
```

Two things stack together here, both traceable directly from that one URL.

## 1. The `/api/v1` path segment is missing — this is on me

Every backend route lives under `/api/v1` (`AuthController` is `@RequestMapping("/api/v1/auth")`,
confirmed) — but the failing request went to `.../auth/register`, not `.../api/v1/auth/register`.

Here's why: `client.ts` had `const BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api/v1'` —
when `VITE_API_BASE_URL` is set, it **replaces** the base entirely rather than adding to it. If it
got set to just the bare Railway origin (`https://confident-wonder-dev.up.railway.app`, no
`/api/v1` suffix), the `/api/v1` prefix is silently lost, and the relative call `api.post('/auth/register', ...)`
resolves to exactly the broken URL in your screenshot.

My earlier instruction — "set `VITE_API_BASE_URL` to your Railway backend's public URL" — never
said whether `/api/v1` needed to be included. That ambiguity is exactly what caused this. Fixed
two ways:

- **Code hardened, not just documented better:** both `frontend/src/api/client.ts` and
  `admin-portal/src/api/client.ts` now run the value through `normalizeApiBase()`, which produces
  the same correct result whether `VITE_API_BASE_URL` is set to the bare origin or already
  includes `/api/v1` — this exact misconfiguration can't silently break every API call again.
  Added `client.test.ts` (new for the user frontend, extended for admin-portal) covering both
  input forms plus trailing-slash variants.
- **Deployment guide corrected** to state this explicitly, and to reflect that the actual
  deployment is Cloudflare **Pages** (`finora-cng.pages.dev`), not Workers as I'd written before.

## 2. Possibly also `CORS_ORIGINS` — worth checking regardless

Even with the path fixed, the browser error is specifically a **CORS** failure (no
`Access-Control-Allow-Origin` header on the preflight response) — the exact symptom you'd also
get if the Railway backend's `CORS_ORIGINS` env var doesn't list `https://finora-cng.pages.dev`
verbatim (scheme, host, no trailing slash). My earlier deployment guide used
`finora-frontend.siddharthtiwari155.workers.dev` as the example — the actual deployment ended up
on a different domain entirely (Cloudflare Pages, not Workers), so if `CORS_ORIGINS` was ever set
using that example rather than the real domain, that's a second, independent thing to fix.

**Please check Railway's `CORS_ORIGINS` value now and confirm it includes exactly
`https://finora-cng.pages.dev`** (or your actual production Pages domain, if different from what's
in this screenshot).

## What to do

1. Redeploy the frontend with the fixed `client.ts` (this bundle).
2. In Cloudflare Pages' env config, `VITE_API_BASE_URL` can now be either form — but I'd still set
   it to `https://confident-wonder-dev.up.railway.app/api/v1` explicitly, since that's the
   unambiguous, correct value going forward.
3. Confirm Railway's `CORS_ORIGINS` includes `https://finora-cng.pages.dev` exactly.
4. Try register/login again.

## Verification

`tsc -b` clean on both `frontend/` and `admin-portal/`. Couldn't run the actual test suite myself
(no matching native Vitest binary in this sandbox, same constraint as every frontend round) —
please run `npm test` in both and confirm the new `normalizeApiBase` tests pass.
