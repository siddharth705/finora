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
5. [Before running more than one backend instance](#before-running-more-than-one-backend-instance)
6. [Cloudflare (both frontends)](#cloudflare-both-frontends)
7. [Dev environment (admin-portal, frontend, mobile)](#dev-environment-admin-portal-frontend-mobile)
8. [Frontend environment variables](#frontend-environment-variables)

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
| `EMAIL_FROM` | No | `onboarding@resend.dev`<!-- synthetic-ok: Resend's own published sandbox sender address, not customer data --> | "From" address for outgoing email | Only if you have your own verified sender — Resend requires the sending domain to be verified (SPF/DKIM DNS records added in Resend's dashboard) before it will actually send as that address; until verified, sends silently fail or land in spam rather than erroring at boot |
| `EMAIL_FROM_NAME` | No | unset | Optional display name shown alongside `EMAIL_FROM` (`app.email.from-name` — see `EmailProperties`) — e.g. set to `Finora` so outgoing mail shows as `Finora <noreply@yourdomain>` instead of the bare address | Cosmetic only; leaving it unset is safe, just less polished in the recipient's inbox |
| `GOOGLE_APPLICATION_CREDENTIALS` | **Yes — hard boot-time requirement in `prod`** | unset | Absolute path to a Firebase service-account JSON key file; read directly by the Firebase Admin SDK (`FirebaseConfig`), not via a Spring `@ConfigurationProperties` binding — there's no `application.yml` key for this | **No.** `ProductionConfigValidator` refuses to start the `prod` profile unless `PhoneVerificationProvider.isConfigured()` (i.e. this file is present and valid) — see its own doc comment. Phone verification (registration, password reset, authenticated password change) fails with a 503 everywhere else if this is missing. |
| *(unset)* | — | — | `FirebaseConfig.firebaseApp()` logs a warning and yields `Optional.empty()` — the app still starts (outside `prod`), but any phone-verification-gated flow fails until this is set | Acceptable only outside `prod` |
| `GOOGLE_APPLICATION_CREDENTIALS_BASE64` | Only if you use this instead of a mounted/volume file | unset | Railway-friendly alternative to `GOOGLE_APPLICATION_CREDENTIALS`: the entire Firebase service-account JSON key, base64-encoded, as one plain-string variable (Railway's Variables tab stores strings, not files). `backend/docker-entrypoint.sh` decodes it to `/app/firebase-service-account.json` and exports `GOOGLE_APPLICATION_CREDENTIALS` pointing at that file *before* the JVM starts — only when `GOOGLE_APPLICATION_CREDENTIALS` isn't already set directly, so this never overwrites an explicit value. | Same requirement as `GOOGLE_APPLICATION_CREDENTIALS` above — set one or the other |
| `GOOGLE_LOGIN_CLIENT_IDS` | No | empty | D-23 "Sign in with Google" — comma-separated OAuth client id(s) `GoogleIdTokenVerifierService` accepts as a valid audience. A separate registration from `GOOGLE_APPLICATION_CREDENTIALS`/Gmail-sync OAuth above — see `GoogleLoginProperties`'s own doc comment for why. | Yes — unconfigured is a supported state: `POST /api/v1/auth/google` answers 503 and the frontend hides the Google button (see `VITE_GOOGLE_LOGIN_CLIENT_ID` below) entirely; nothing else in the app is affected. |
| `TWO_FACTOR_API_KEY` | No — soft, non-fatal check only | empty | 2Factor API key for real-time transaction alert SMS (`TwoFactorSmsProvider`) — scoped to `TransactionService.create()`'s manual-entry path only, never authentication OTPs (Firebase Phone Authentication owns those). Empty falls back to `NoOpSmsProvider` (logs instead of sending). | **Yes.** Unlike `RESEND_API_KEY`/`GOOGLE_APPLICATION_CREDENTIALS`, `ProductionConfigValidator` only logs a startup warning if this is unset — never refuses to boot — since a missed transaction alert is a degraded notification, not a security gap (see `SmsProperties`'s own doc comment). |
| `FINORA_SETUP_KEY` | No | empty (auto-generated + written to `.finora/installation.key`) | First-run bootstrap installation key | See `docs/bootstrap-setup-future-work.md` — set explicitly rather than relying on a written file in any deployment without a persistent, host-readable filesystem |
| `TRUST_PROXY_HEADERS` | **Yes, on Railway** | `false` | Whether `RateLimitFilter` trusts `X-Forwarded-For` for the real client IP | **Must be `true` on Railway** (or any deployment behind a real reverse proxy) — otherwise every user shares one rate-limit bucket. Must stay `false` anywhere not behind a trusted proxy, or rate limiting can be bypassed by spoofing the header. Like `TWO_FACTOR_API_KEY` above, `ProductionConfigValidator` only logs a startup warning if this is left at its default in `prod` — it never refuses to boot over this one. |
| `PASSWORD_CHANGE_SESSION_EXPIRY_MINUTES` | No | `15` | How long a started Change Password flow (`PasswordChangeSession`) stays usable before `verify-otp`/`complete` start rejecting it | Yes |
| `IMPORT_MAX_CONCURRENT` | No | `6` | Max concurrent statement-import requests (`ImportConcurrencyLimiter`), deliberately conservative relative to `DB_POOL_MAX_SIZE` so imports can't starve every other endpoint's DB usage. BH-043: past this limit, requests are rejected immediately (HTTP 503, `IMPORT_006`) rather than queued -- there is no wait-timeout variable to set anymore | Yes |
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

**Without `GOOGLE_APPLICATION_CREDENTIALS` set, you can't get past registration.** Phone
verification is enforced server-side (`PhoneVerificationFilter`) before any account reaches the
dashboard, and every verification call 503s until the Firebase Admin SDK is configured
(`FirebaseConfig`). See the `GOOGLE_APPLICATION_CREDENTIALS` / `GOOGLE_APPLICATION_CREDENTIALS_BASE64`
rows in the [environment variable audit](#environment-variable-audit-backend) above, plus the six
`VITE_FIREBASE_*` vars in [Frontend environment variables](#frontend-environment-variables), for
what a fresh clone needs before phone verification actually works.

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
JWT_SECRET=<a real random 32+ char value — see "Generating JWT_SECRET" below; never reuse an example>
CORS_ORIGINS=https://app.fynora.net,https://admin.fynora.net
APP_BASE_URL=https://app.fynora.net
ADMIN_APP_BASE_URL=https://admin.fynora.net
RESEND_API_KEY=<your real Resend API key>
EMAIL_FROM=noreply@fynora.net
EMAIL_FROM_NAME=Fynora
# Either a mounted file path directly, or GOOGLE_APPLICATION_CREDENTIALS_BASE64 instead (see below
# and the environment variable audit table above) -- Railway's Variables tab only stores strings.
GOOGLE_APPLICATION_CREDENTIALS=/path/to/firebase-service-account.json
TRUST_PROXY_HEADERS=true
TWO_FACTOR_API_KEY=<your real 2Factor API key>
```

`GOOGLE_APPLICATION_CREDENTIALS` must point at a real, readable file on the deployed instance —
on Railway that typically means a "Raw file" volume mount, or setting `GOOGLE_APPLICATION_CREDENTIALS_BASE64`
instead (the whole service-account JSON, base64-encoded, as a plain string variable) and letting
`backend/docker-entrypoint.sh` decode it to a real file and export `GOOGLE_APPLICATION_CREDENTIALS`
before the JVM starts, since Railway's Variables tab itself only stores strings, not files.
Download the service-account key from Firebase Console → Project Settings →
Service Accounts → "Generate new private key"; never commit it to source control.

`TWO_FACTOR_API_KEY` is a plain string value (unlike the credentials file above), so it's a normal
Railway "Variable" entry — Variables tab → New Variable → paste the key from your 2Factor
dashboard. Unlike every other secret in this table, leaving it unset does NOT block startup or
degrade security; it just means `TwoFactorSmsProvider` falls back to logging transaction alerts
instead of sending them, and `ProductionConfigValidator` prints one startup warning line about it.

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

### Generating `JWT_SECRET` — use hex

```bash
openssl rand -hex 32
```

Hex rather than base64, and the reason is measurable rather than stylistic. `ProductionConfigValidator`
rejects any secret containing a placeholder marker (`change-me`, `sample`, `dummy`, `insecure`, …),
matched case-insensitively as a substring. A randomly generated secret can contain one by chance:
over 2,000,000 generated 48-byte values, **1 base64url secret was rejected** (it happened to contain
`dumMyy`), a rate of roughly 1 in 2 million.

In **hex it is structurally impossible** — not merely unlikely. Every marker contains at least one
character outside `[0-9a-f]`, so no hex secret can ever match one. Verified by the same run: 0
rejections out of 2,000,000.

If a generated secret is ever rejected at boot, that is this collision and not a bug. Generate
another one; with hex you will not see it.

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

## Before running more than one backend instance

**Today this app is deployed as a single Railway instance, and two rate-limiting controls depend on
that being true.** Raising the replica count is a one-click change in Railway; nothing in the
application will complain, no test will fail, and no log line will appear. The controls simply
become weaker in proportion to the instance count. This section exists so that decision is made
knowingly rather than discovered later.

### What breaks silently, and by how much

| Control | Where | Effect of running N instances |
|---|---|---|
| Per-IP rate limits (`RateLimiter`, used by `RateLimitFilter`) | in-memory `ConcurrentHashMap`, per JVM | Every limit becomes **N× more permissive**. Login goes from 10 attempts/min/IP to 10N — this is the per-IP half of the credential-stuffing defence. Registration, forgot-password, import staging and password-change scale the same way. |
| Import concurrency (`ImportConcurrencyLimiter`) | in-process `Semaphore` (BH-043: non-fair -- see the env-var table above), `app.import.max-concurrent` (default 6) | **6N** imports can run at once, each holding statement bytes in memory and competing for that instance's own DB connections. The cap exists to stop a burst of uploads exhausting heap; N instances raise the real ceiling without raising the memory available to any one of them. |

Both classes say so in their own doc comments. Neither is a bug — an in-process limiter is the
right amount of engineering for one instance, and reaching for Redis before there is a second
instance would be infrastructure with no payoff.

### What is NOT affected

Worth stating so the list above is not over-read:

- **Account lockout is safe.** It is persisted (`users.failed_login_attempts`, `users.locked_until`,
  thresholds in `platform_settings`), so the per-account half of the login defence works unchanged
  across any number of instances. Only the per-IP half degrades.
- **Refresh-token rotation and theft detection are safe** — entirely database-backed.
- **Import-session cleanup is safe.** It is piggybacked on a user's next `stage()` call rather than
  a scheduled sweep, so it still runs whichever instance serves that request. There are no
  `@Scheduled` or `@Async` jobs anywhere in the backend, so there is nothing that would double-run.

### The precondition

Before increasing the replica count:

1. Move `RateLimiter`'s counters to a store shared by every instance (Redis is the obvious choice;
   a fixed window keyed by IP is all that is needed, matching today's semantics).
2. Decide what `app.import.max-concurrent` should mean with N instances — either divide it by the
   replica count so the global ceiling is unchanged, or move the permit pool out of process too.
3. Re-check `DB_POOL_MAX_SIZE` (default 10) against Postgres's `max_connections`: the pool is
   **per instance**, so N instances open up to 10N connections.

Do the first two before, not after. Both failure modes are silent, and the first one weakens a
security control.

## Cloudflare (both frontends)

> **Decision on record — where each thing lives.** PostgreSQL stays on **Railway**, co-located with
> the backend, because the import pipeline makes many round-trips inside one transaction. Neon is
> reconsidered only if point-in-time recovery, per-branch databases, or a reason to separate
> database from application hosting becomes real — see
> [statement-storage-migration.md](../../architecture/data/statement-storage-migration.md) §7, which also records the
> HikariCP caution that would apply. Cloudflare's role today is **Pages for both frontends**;
> **R2 for uploaded statement files** is proposed but not built — statements currently live in
> PostgreSQL as `BYTEA`.

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

### `VITE_SENTRY_DSN` (both frontends) — crash reporting

Optional, and unset is a valid, fully-working configuration: `src/lib/monitoring.ts` no-ops
without it. But leaving it unset means a crash in either web app is invisible to you — which is
the exact situation these apps were already in, while the mobile app had crash reporting. A blank
white page from a render error is the failure this catches, and there is no other mechanism that
would tell you it happened.

```
VITE_SENTRY_DSN=https://<key>@<org>.ingest.sentry.io/<project>
```

**It must be set as a *build* environment variable, not a runtime one.** Vite inlines
`import.meta.env.*` at build time, so a value added to the Pages project after a deployment has
already been built has no effect until the next build. This is the same class of mistake as the
`VITE_API_BASE_URL` bug above: everything looks configured and nothing reports.

The upside of that inlining is worth knowing, and was measured rather than assumed: with the DSN
unset the entire Sentry SDK is tree-shaken out of the bundle (verified — zero occurrences of
`sentry` in `dist/`, user app at 820 kB). With it set the same build is 911 kB, about **+30 kB
gzipped**. So crash reporting costs nothing at all until you actually turn it on, and the cost when
you do is a known number.

What leaves the browser is deliberately much narrower than Sentry's defaults, because this app's
URLs carry the ledger search term (`?q=`), password-reset tokens (`?token=`), and — in the admin
portal — the ids of the customers an admin was viewing. See `src/lib/monitoring.ts` in either app
for exactly what is stripped and why; the scrubbers are unit-tested, because scrubbing that
silently stops working looks identical to scrubbing that works.

**Also verify `CORS_ORIGINS` on the Railway backend matches your ACTUAL deployed frontend
origin(s) exactly** — scheme, host, no trailing slash. Cloudflare Pages assigns its own
`<project-name>.pages.dev` domain (and a different one per preview deployment) by default; once a
custom domain is attached (Pages project → Custom domains — e.g. `app.fynora.net` /
`admin.fynora.net`, both proxied through the same Cloudflare account the apex domain's DNS
lives in), that becomes the real production origin and `CORS_ORIGINS`/`APP_BASE_URL`/
`ADMIN_APP_BASE_URL` on the backend must be updated to match it — the `.pages.dev` origin keeps
working alongside a custom domain (Cloudflare doesn't disable it), so nothing breaks immediately if
you forget, but it means the "production" URL and the URL the backend actually trusts have quietly
diverged. A mismatch here produces the same "blocked by CORS policy" browser error as the
`/api/v1` bug above, so if requests still fail after fixing `VITE_API_BASE_URL`, this is the next
thing to check. Attaching a custom domain to an existing Pages project does **not** require a new
build — unlike a `VITE_*` variable change, this one takes effect without a redeploy.

**Two things a domain migration is easy to forget, neither of which fails loudly:**
- **Resend domain verification.** `EMAIL_FROM`/`RESEND_API_KEY` being set is not the same as Resend
  being *willing* to send as that address — the sending domain must be added and verified in
  Resend's dashboard (Domains → Add Domain), which means adding the SPF/DKIM records Resend
  provides to the domain's DNS (Cloudflare, in our case). Skip this and sends either fail silently
  or land in spam; `ProductionConfigValidator` has no way to check it, since "is this domain
  verified" is a fact that lives entirely on Resend's side.
- **Firebase Authorized Domains.** Phone verification (registration, password reset, authenticated
  password change — see `FirebaseConfig`) runs client-side via the Firebase Web SDK, which refuses
  to complete `signInWithPhoneNumber` from any origin not on Firebase Console → Authentication →
  Settings → **Authorized domains**. The `.pages.dev` domains are on that list today; the new custom
  domains are a **different origin** and won't be, until added there manually. Nothing else in this
  guide's checklist (CORS, `APP_BASE_URL`, Resend) touches this list — it's tracked only by Firebase,
  so it's the one step a domain cutover silently breaks if skipped: every OTP screen on the new
  domain fails with `auth/unauthorized-domain` while the rest of the app works normally.

## Dev environment (admin-portal, frontend, mobile)

The backend already runs on two Railway environments — Production (`api.fynora.net`) and Dev
(`dev-api.finoratech.info`). This section covers giving the three client surfaces (admin-portal,
frontend, mobile) a matching Dev tier, so a feature can be exercised end-to-end against a live
backend before it ever touches production data, Firebase, or real Google accounts.

**Nothing shared with Production here — a deliberately separate Firebase project.** Production's
convention (one Firebase project, same values in both `frontend/` and `admin-portal/` — see
"Frontend environment variables" below) still holds *within* each tier, but Dev gets its own
project, its own service-account key, and its own Google Sign-In OAuth client, not Production's.
Testing against Dev should never send a real SMS through Production's Firebase project or
authenticate against a real Google account tied to Production's OAuth consent screen.

**`dev` is a persistent git branch**, not a feature branch, protected with the same ruleset as
`main` (required status checks, no direct pushes, `enforce_admins` on — see "Branch protection"
below). `.github/workflows/sync-dev-branch.yml` keeps it caught up with `main`'s tip on every push
to `main` by opening (or reusing) a `main → dev` PR and enabling auto-merge on it — a direct push
would be rejected by the protection rule itself, so this goes through the same required checks
(`Backend (Java 25)`, `User frontend`, `Admin portal`, `Mobile (Expo)`, `End-to-end smoke
(Chromium)`) as any other change to a protected branch, rather than bypassing them. Cloudflare
Pages binds `dev-app.finoratech.info` / `dev-admin.finoratech.info` to this branch as a
**branch-alias custom domain** (Pages project → Settings → Custom domains → set up a custom
domain, then repoint that hostname's DNS CNAME at `dev.<pages-project>.pages.dev` instead of the
bare `<pages-project>.pages.dev`) — not a second Pages project.

### Branch protection (`main` and `dev`)

Both branches require: a pull request (no direct pushes, `enforce_admins` enabled so this applies
to admins too), the same 5 CI checks passing, and `strict: true` (the PR's branch must be
up-to-date with the base before merging). Deliberately **no required approving review count** —
this repo has no second human reviewer today, and requiring one would block merging your own PRs
entirely. Revisit this once that changes. The repo's "Allow auto-merge" setting is on, which
`sync-dev-branch.yml` above depends on.

Cloudflare Pages' environment-variable UI has only two buckets, Production and Preview — there is
no native per-branch scoping. The Dev-specific `VITE_*` values (the six `VITE_FIREBASE_*` keys,
`VITE_API_BASE_URL=https://dev-api.finoratech.info`, plus `VITE_GOOGLE_LOGIN_CLIENT_ID` on
`frontend/` and `VITE_BACKEND_ORIGIN` on `admin-portal/`) go in the **Preview** bucket — which
means every open PR's preview deployment also picks them up, not just the `dev` branch. That's the
intended outcome: no PR preview should ever be able to reach Production's Firebase project or data.

**Railway's Dev environment** needs its own `CORS_ORIGINS`/`APP_BASE_URL`/`ADMIN_APP_BASE_URL`
(pointed at the two `dev-*` origins, same format as the Production values documented above) plus its
own `GOOGLE_APPLICATION_CREDENTIALS_BASE64` and `GOOGLE_LOGIN_CLIENT_IDS` (the Dev Firebase
project's own service-account key and OAuth client id — see the Railway section above for exactly
how each of those is shaped; the Dev environment's copies just point at the new project instead of
the existing one).

**Mobile has no cloud-built Dev profile.** `mobile/eas.json`'s `dev` build profile inlines
`EXPO_PUBLIC_API_BASE_URL=https://dev-api.finoratech.info` directly (no confidentiality reason to
route a public API origin through EAS's environment-variable store — see `mobile-setup.md` for why
`EXPO_PUBLIC_*` values are inlined into the client bundle regardless), but a genuinely custom EAS
environment name for the Dev Firebase config files is only available on a paid EAS plan. Build the
`dev` profile locally instead (`eas build --profile dev --platform android --local`, and the iOS
equivalent), with the Dev project's `google-services.json`/`GoogleService-Info.plist` physically
present in `mobile/` at build time — same file-based convention the existing `development` profile
already uses. See `docs/engineering/mobile/mobile-setup.md` for the full walkthrough.

## Frontend environment variables

| App | Variable | Required in prod? | Purpose |
|---|---|---|---|
| `frontend/` | `VITE_API_BASE_URL` | **Yes** | Backend's absolute origin for every API call |
| `frontend/` | `VITE_LOGODEV_TOKEN` | No | Optional bank- and merchant-logo lookups (BankLogo.tsx, MerchantLogo.tsx) via Logo.dev; unset just skips that step. Free-tier commercial use needs attribution -- see `frontend/.env.example` |
| `frontend/` | `VITE_GOOGLE_LOGIN_CLIENT_ID` | No | D-23 "Sign in with Google" web OAuth client id (`GoogleSignInButton.tsx`) — must match one of the backend's `GOOGLE_LOGIN_CLIENT_IDS` above. Unset hides the Google button entirely rather than rendering one that can't work. |
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
