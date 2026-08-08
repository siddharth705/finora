# Incident: login unusable after a deploy, with an unrecoverable "Try again"

**Date:** 2026-08-08
**Status:** Fixed — recovery shipped, see §4.
**Severity:** Low impact, high annoyance. No data at risk, backend unaffected, and a manual hard
refresh always fixed it. It gets a record because it recurs on *every* deploy, and because the
recovery UI the app already had made it look permanent to the user.

---

## 1. What happened

A user on `app.finoratech.info/login` saw the app's own error panel — "This page didn't load
correctly" — and clicking **Try again** did nothing. The console showed:

```
Failed to load module script: Expected a JavaScript-or-Wasm module script but the server
responded with a MIME type of "text/html".
TypeError: Failed to fetch dynamically imported module:
https://app.finoratech.info/assets/Login-00kC5-u3.js
```

## 2. Why

Vite content-hashes every chunk, so a deploy replaces `Login-<hash>.js` with a new filename and
deletes the old one. **A tab that was already open across the deploy still holds the old names in
its in-memory module graph.** Navigating client-side to a lazy route then requests a file that no
longer exists.

Two things turned that into what the user saw:

- **The 404 that wasn't.** Cloudflare Pages answers unmatched paths with the SPA fallback, which is
  right for routes and wrong for assets. The missing chunk came back as `index.html` with
  `200 text/html`, so the browser reported a MIME mismatch rather than a plain 404. Measured on
  production: `/assets/does-not-exist.js` → `200 text/html`.
- **A retry that could not work.** `ErrorBoundary.reset()` cleared `hasError` and re-rendered. React
  re-attempted the same lazy import, requested the same missing URL, and failed identically. The
  button was an infinite loop of one failure.

**This was not a caching problem, and cache headers do not fix it.** Production already serves
`index.html` as `max-age=0, must-revalidate`. Client-side navigation never re-fetches the HTML at
all, so no header on any file changes the outcome — the tab simply outlived the deploy.

Nor was it a backend fault: `api.finoratech.info/actuator/health` returned `UP` and
`POST /api/v1/auth/login` returned a normal `400` throughout.

## 3. Why nothing caught it

The e2e smoke suite loads a fresh page against a single build. It never crosses a deploy boundary,
which is the only condition under which this can happen. No test at any level was wrong; the
scenario was outside all of them, and largely still is.

## 4. The fix

- **`frontend/src/lib/staleChunk.ts`** — recognises the module-load failure in each engine's
  wording and reloads the document once. Guarded by a `sessionStorage` marker with a 30-second
  cooldown: a second identical failure inside that window means the *fresh* HTML failed too, which
  is a broken deploy rather than a stale tab, so the panel is shown instead of reloading again. An
  unguarded reload here is a reload loop the user cannot read an error through — strictly worse than
  the bug.
- **`ErrorBoundary`** — routes that one error class to the reload, and deliberately does not report
  a recovered stale chunk to Sentry. It is expected and self-healing on every release; reporting it
  would bury the signal that matters. A failure the guard *refused* does report, because that one is
  real.
- **`frontend/public/_redirects`** — `/assets/*` now returns a genuine 404 instead of falling
  through to the SPA HTML. Scoped to that one directory: every legitimate URL under it is generated
  by the build, so a miss always means "this file is gone", never "this is a route".
- **`frontend/public/_headers`** — hashed assets become `immutable, max-age=31536000`. A performance
  change only; it does not affect this incident either way.

## 5. What is still true

A user with the app open during a deploy will still hit one failed import. The difference is that
it now recovers itself in a reload instead of stranding them behind a button that cannot help.

Removing the failure entirely means serving old chunks alongside new ones for a grace period, which
Pages does not do natively and which is not worth building for a single-page reload.
