# Deployment Guide

Covers running Finora's backend and both frontends (`frontend/`, the user app; `admin-portal/`,
the admin app) locally, in Docker, on Railway (backend + Postgres), and on Cloudflare (Pages or Workers)
(both frontends). Written as part of the production-readiness pass moving Finora from a purely
local setup to a real cloud deployment (Railway + Cloudflare).

The goal this documents: the same backend codebase runs unmodified locally, in Docker, on
Railway, or on any other cloud provider (AWS/Azure/GCP) — every environment-specific value comes
from an environment variable, never a hardcoded value in source.

## Contents

1. [Environment variable audit (backend)](#environment-variable-audit-backend)
2. [Local development](#local-development)
3. [Docker (docker-compose)](#docker-docker-compose)
4. [Railway (backend + Postgres)](#railway-backend--postgres)
5. [Cloudflare (both frontends)](#cloudflare-both-frontends)
6. [Frontend environment variables](#frontend-environment-variables)

---

## Environment variable audit (backend)

Every environment variable the backend reads anywhere, extracted directly from
`application.yml`/`application-*.yml` (the single source of truth — nothing in the Java code
reads an env var that isn't routed through one of these files). "Safe for production" means: safe
to leave at its default: local dev defaults, or must be explicitly set.

| Variable | Required in prod? | Default (dev/local only) | Used for | Safe to leave at default in prod? |
|---|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | Yes | `dev` | Selects which `application-*.yml` overlay applies | **No** — must be `prod` |
| `PORT` | Set automatically by Railway | `8080` | Which port the app listens on (`server.port`) | Yes — Railway sets this itself |
| `DB_HOST` | Yes | `localhost` | Postgres host | No |
| `DB_PORT` | Yes | `5432` | Postgres port | Usually fine as-is |
| `DB_NAME` | Yes | `finora` | Postgres database name | No |
| `DB_USER` | Yes | `finora` | Postgres username | No |
| `DB_PASSWORD` | Yes | `finora` | Postgres password | **No — never leave this default in prod.** `ProductionConfigValidator` now refuses to start the `prod` profile if this is still `finora`. |
| `DB_POOL_MAX_SIZE` | No | `10` | HikariCP max connections | Check against your Postgres plan's own connection ceiling before relying on the default |
| `DB_POOL_MIN_IDLE` | No | `2` | HikariCP min idle connections | Yes |
| `JWT_SECRET` | Yes | placeholder string | Signs/verifies access + refresh tokens (HS256, needs 32+ chars) | **No — never leave this default in prod.** `ProductionConfigValidator` refuses to start if this is unset, still the placeholder, or under 32 characters. |
| `JWT_EXPIRATION_MS` | No | `900000` (15 min) | Access token lifetime | Yes |
| `JWT_REFRESH_EXPIRATION_MS` | No | `2592000000` (30 days) | Refresh token lifetime | Yes |
| `CORS_ORIGINS` | Yes | both local dev ports | Comma-separated allowed origins (`CorsConfig`) | **No** — must list your real deployed frontend origin(s), no wildcard |
| `APP_BASE_URL` | Yes | `http://localhost:5173` | Base URL used to build links in emails (password reset, etc. — see `EmailConfig`) | **No** — must be your real deployed frontend's URL, or generated links point at localhost |
| `ADMIN_APP_BASE_URL` | Recommended | `http://localhost:5174` | Same purpose as `APP_BASE_URL`, but for the admin portal specifically — see `EmailProperties.resolveBaseUrl()`. The user frontend and admin portal are separate deployed apps at separate origins, each with its own `/reset-password` page, but there's no separate admin auth service; without this set, an admin's "Forgot Password" links to the *user* app's reset page instead of the admin portal's own. Picked automatically from the request's `Origin` header — no frontend changes needed either way. | Leave unset only if you're fine with admin password resets linking to the wrong app |
| `RESEND_API_KEY` | **Yes — hard boot-time requirement in `prod`** | empty | Resend API key; empty falls back to `NoOpEmailService` (logs the link instead of sending) | **No.** Unset, this used to just mean silent no-op emails; `ProductionConfigValidator` now refuses to *start* the `prod` profile at all if this is blank, because the actual failure mode is worse than "no email" — `NoOpEmailService.isConfigured()` returning `false` makes `AuthService.forgotPassword()` return the raw, valid reset link directly in the API response instead, a full account-takeover primitive for anyone who knows a user's email address. |
| `EMAIL_FROM` | No | `onboarding@resend.dev` | "From" address for outgoing email | Only if you have your own verified sender |
| `GOOGLE_APPLICATION_CREDENTIALS` | **Yes — hard boot-time requirement in `prod`** | unset | Absolute path to a Firebase service-account JSON key file; read directly by the Firebase Admin SDK (`FirebaseConfig`), not via a Spring `@ConfigurationProperties` binding — there's no `application.yml` key for this | **No.** `ProductionConfigValidator` refuses to start the `prod` profile unless `PhoneVerificationProvider.isConfigured()` (i.e. this file is present and valid) — see its own doc comment. Phone verification (registration, password reset, authenticated password change) fails with a 503 everywhere else if this is missing. |
| *(unset)* | — | — | `FirebaseConfig.firebaseApp()` logs a warning and yields `Optional.empty()` — the app still starts (outside `prod`), but any phone-verification-gated flow fails until this is set | Acceptable only outside `prod` |
| `FINORA_SETUP_KEY` | No | empty (auto-generated + written to `.finora/installation.key`) | First-run bootstrap installation key | See `docs/bootstrap-setup-future-work.md` — set explicitly rather than relying on a written file in any deployment without a persistent, host-readable filesystem |
| `TRUST_PROXY_HEADERS` | **Yes, on Railway** | `false` | Whether `RateLimitFilter` trusts `X-Forwarded-For` for the real client IP | **Must be `true` on Railway** (or any deployment behind a real reverse proxy) — otherwise every user shares one rate-limit bucket. Must stay `false` anywhere not behind a trusted proxy, or rate limiting can be bypassed by spoofing the header. |
| `UPLOAD_MAX_FILE_SIZE` / `UPLOAD_MAX_REQUEST_SIZE` | No | `10MB` | Multipart upload size limits (CSV/PDF statement import) | Yes |

## Local development

```bash
cd backend
mvn spring-boot:run   # SPRING_PROFILES_ACTIVE defaults to dev, connects to localhost:5432
```

Requires a local Postgres running with the `finora`/`finora`/`finora` db/user/password (or set
`DB_*` env vars to point elsewhere). Frontends run via their own Vite dev servers (`npm run dev`
in `frontend/` and `admin-portal/`), each proxying `/api` to `localhost:8080` — see each app's
`vite.config.ts`. Neither frontend needs any env var set for local dev; `VITE_API_BASE_URL` /
`VITE_BACKEND_ORIGIN` are only relevant once deployed (see below).

## Docker (docker-compose)

```bash
docker compose up --build -d
```

`docker-compose.yml` already sets every required backend env var for a self-contained local stack
(Postgres + backend, `dev` profile). Nothing here reflects real production values — the JWT
secret and DB password in `docker-compose.yml` are dev-only placeholders, same as
`application.yml`'s own defaults.

## Railway (backend + Postgres)

Railway auto-deploys the backend from `backend/Dockerfile` on every push to `main`.
`backend/railway.json` tells Railway to health-check `/actuator/health` rather than just checking
that the process started.

**Required Railway environment variables** (set these in the Railway service's Variables tab —
none of them belong in source control):

```
SPRING_PROFILES_ACTIVE=prod
DB_HOST=<Railway Postgres internal host>
DB_PORT=<Railway Postgres port>
DB_NAME=<Railway Postgres database name>
DB_USER=<Railway Postgres username>
DB_PASSWORD=<Railway Postgres password>
JWT_SECRET=<a real random 32+ character value — generate one, don't reuse any example>
CORS_ORIGINS=https://finora-cng.pages.dev,https://finora-admin.pages.dev
APP_BASE_URL=https://finora-cng.pages.dev
ADMIN_APP_BASE_URL=https://finora-admin.pages.dev
RESEND_API_KEY=<your real Resend API key>
EMAIL_FROM=<your verified sender address>
GOOGLE_APPLICATION_CREDENTIALS=/path/to/firebase-service-account.json
TRUST_PROXY_HEADERS=true
```

`GOOGLE_APPLICATION_CREDENTIALS` must point at a real, readable file on the deployed instance —
on Railway that typically means committing the JSON as a "Raw file" volume mount or writing it out
from a base64'd env var at container startup, since Railway's Variables tab itself only stores
strings, not files. Download the service-account key from Firebase Console → Project Settings →
Service Accounts → "Generate new private key"; never commit it to source control.

`CORS_ORIGINS` must list **both** frontend origins, comma-separated, no spaces around the comma
(or trim them — `CorsConfig` already trims each entry) — a mismatch here is exactly what produces
a "blocked by CORS policy" browser error on whichever app isn't listed.

Railway sets `PORT` itself — don't set it manually. The four Railway Postgres `DB_*` values are
available directly from the Postgres service's own "Connect" tab once you've provisioned it and
linked it to the backend service.

If `FINORA_SETUP_KEY` isn't set, the app starts fine — first-run bootstrap just falls back to
writing/logging a generated key instead (see `docs/bootstrap-setup-future-work.md`).
**`GOOGLE_APPLICATION_CREDENTIALS` is different: it's a hard boot-time requirement, same as
`RESEND_API_KEY`** — see the next paragraph.

`ProductionConfigValidator` refuses to start the app at all (loud failure at boot, not a silent
insecure default, and not a `restartPolicyMaxRetries` crash-loop you have to dig through logs to
diagnose) if, while `SPRING_PROFILES_ACTIVE=prod`:
- `JWT_SECRET` or `DB_PASSWORD` are still their local-dev placeholder values,
- `RESEND_API_KEY` is blank, or
- `PhoneVerificationProvider.isConfigured()` is false — i.e. `GOOGLE_APPLICATION_CREDENTIALS`
  isn't set to a valid, readable Firebase service-account key file.

**Set all four categories above *before* the first deploy with `SPRING_PROFILES_ACTIVE=prod`.**
A real incident already happened here (back when this was `RESEND_API_KEY`/SMS-provider
credentials, before the Firebase migration): a deploy went out with `prod` active but required
config unset, and Railway crash-looped the service (`restartPolicyMaxRetries: 5` in
`railway.json` then leaves it "Crashed") — burning through the deploy's log history and free-tier
build minutes before the cause was found. Two ways out if this happens to you:
1. **Fix it properly** — set the real `RESEND_API_KEY` and a valid `GOOGLE_APPLICATION_CREDENTIALS`
   (see above) and redeploy.
2. **Unblock immediately, fix later** — remove or change `SPRING_PROFILES_ACTIVE` so it isn't
   `prod` (e.g. delete the variable, or set it to `dev`), then redeploy. This is a deliberate,
   temporary trade: `ProductionConfigValidator` only runs in the `prod` profile, so this reopens
   the exact holes it exists to catch (password-reset links leaking in API responses instead of
   being emailed; phone verification failing closed with a 503) — acceptable while you're the only
   person using the deployment to test, not once real users' accounts are on it.

## Cloudflare (both frontends)

Both `frontend/` and `admin-portal/` build as static Vite apps. As actually deployed, that's
Cloudflare Pages (not Workers — update this section if that changes). Whatever your Cloudflare
build pipeline uses for env injection (a Pages project's Settings → Environment variables, or
Wrangler's `[vars]` if using Workers instead), set:

```
VITE_API_BASE_URL=https://<your Railway backend's public domain>
```

**The value can be either the bare origin or the origin with `/api/v1` already appended — both
now work correctly** (see `normalizeApiBase()` in `frontend/src/api/client.ts` /
`admin-portal/src/api/client.ts`). This is a fix, not just a clarification: the bare-origin form
is what actually got set in production once, and every API call silently lost the `/api/v1`
segment every backend route lives under — `register`/`login` (and everything else) hit
`<origin>/auth/register` instead of `<origin>/api/v1/auth/register`, a route that doesn't exist,
which the browser reported as a CORS failure rather than a 404 (the OPTIONS preflight itself
never matched a route to succeed against). `normalizeApiBase()` now produces the same correct
result either way, so this specific misconfiguration can't silently break every API call again —
but there's no reason not to just include `/api/v1` explicitly when setting this.

This is the fix for the frontend not being able to reach the backend at all — see
`frontend/src/api/client.ts`'s own doc comment for the full explanation: the relative `/api/v1`
path this used to hardcode only ever worked through Vite's *dev-server* proxy, which has no
effect at all on the built, deployed static output.

`admin-portal/` additionally has `VITE_BACKEND_ORIGIN`, used only for a couple of direct
human-facing links (Swagger/Actuator) that can't go through the API client at all — set it to the
same Railway backend URL as `VITE_API_BASE_URL` above (bare origin, no `/api/v1` — this one really
is just the origin, used to build a Swagger UI link directly).

**Also verify `CORS_ORIGINS` on the Railway backend matches your ACTUAL deployed frontend
origin(s) exactly** — scheme, host, no trailing slash. Cloudflare Pages assigns its own
`<project-name>.pages.dev` domain (and a different one per preview deployment) — confirm the
production domain in the Cloudflare dashboard rather than assuming it matches whatever was
planned or referenced elsewhere, and update `CORS_ORIGINS` to match it exactly. A mismatch here
produces the same "blocked by CORS policy" browser error as the `/api/v1` bug above, so if
requests still fail after fixing `VITE_API_BASE_URL`, this is the next thing to check.

## Frontend environment variables

| App | Variable | Required in prod? | Purpose |
|---|---|---|---|
| `frontend/` | `VITE_API_BASE_URL` | **Yes** | Backend's absolute origin for every API call |
| `frontend/` | `VITE_BRANDFETCH_CLIENT_ID` | No | Optional bank-logo lookups; unset just skips that step |
| `admin-portal/` | `VITE_API_BASE_URL` | **Yes** | Same as above, this app's own API client |
| `admin-portal/` | `VITE_BACKEND_ORIGIN` | Yes, if Diagnostics' Swagger/Actuator links are used | Direct human-facing links that can't go through the API client |
| both | `VITE_FIREBASE_API_KEY` / `VITE_FIREBASE_AUTH_DOMAIN` / `VITE_FIREBASE_PROJECT_ID` / `VITE_FIREBASE_STORAGE_BUCKET` / `VITE_FIREBASE_MESSAGING_SENDER_ID` / `VITE_FIREBASE_APP_ID` | **Yes** | Firebase Web SDK config (`lib/firebase.ts` in each app) that powers Firebase Phone Authentication (OTP send/confirm for registration, password reset, and admin password change) | **No.** Without these, `getFirebaseAuth()` throws the moment a phone-verification screen is actually used (`VerifyPhone.tsx`/`ResetPassword.tsx`) — everything else in the app keeps working since Firebase init is lazy, not at module load. |

Copy Firebase Console → Project Settings → General → "Your apps" → the SDK config snippet's six
values directly into the six `VITE_FIREBASE_*` vars above — this is the same Firebase project the
backend's `GOOGLE_APPLICATION_CREDENTIALS` service account belongs to, and the same values in both
`frontend/` and `admin-portal/`. Not secrets in the way an API key to a paid/quota-limited service
would be (Firebase's own docs treat this config as safe to ship in a client bundle; access control
is enforced server-side via the Admin SDK, not by hiding this object) — but still real per-project
values, not placeholders.

`.env.example` in each app's root documents these with the same detail as the table above — copy
to `.env.local` for local overrides, or configure via your deploy pipeline's own env injection for
the actual Cloudflare deployment.
