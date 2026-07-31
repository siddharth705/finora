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
| `RESEND_API_KEY` | Yes (to send real email) | empty | Resend API key; empty falls back to `NoOpEmailService` (logs the link instead of sending) | No — leaving this unset means no real emails ever get sent |
| `EMAIL_FROM` | No | `onboarding@resend.dev` | "From" address for outgoing email | Only if you have your own verified sender |
| `TWILIO_ACCOUNT_SID` / `TWILIO_AUTH_TOKEN` / `TWILIO_FROM_NUMBER` | Yes (to send real SMS) | empty | Twilio credentials for OTP SMS; empty falls back to logging the OTP instead of sending it | No — same reasoning as `RESEND_API_KEY` |
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
CORS_ORIGINS=https://finora-frontend.siddharthtiwari155.workers.dev
APP_BASE_URL=https://finora-frontend.siddharthtiwari155.workers.dev
RESEND_API_KEY=<your real Resend API key>
EMAIL_FROM=<your verified sender address>
TRUST_PROXY_HEADERS=true
```

Railway sets `PORT` itself — don't set it manually. The four Railway Postgres `DB_*` values are
available directly from the Postgres service's own "Connect" tab once you've provisioned it and
linked it to the backend service.

If `TWILIO_*` and `FINORA_SETUP_KEY` aren't set, the app starts fine (see the audit table above)
— but real SMS/first-run bootstrap won't behave the way they do in local dev, so decide those
deliberately rather than by omission.

`ProductionConfigValidator` will refuse to start the app (loud failure at boot, not a silent
insecure default) if `JWT_SECRET` or `DB_PASSWORD` are still their local-dev placeholder values
while `SPRING_PROFILES_ACTIVE=prod` — this is intentional and is the safety net for forgetting to
set one of the two most security-critical variables above.

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

`.env.example` in each app's root documents these with the same detail as the table above — copy
to `.env.local` for local overrides, or configure via your deploy pipeline's own env injection for
the actual Cloudflare deployment.
