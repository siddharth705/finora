# Deployment-readiness memo — gap analysis against the actual codebase

Went through the memo point by point against what's actually built, rather than treating it as a
blind to-do list. Good news first: **most of it is already done.**

## Already fully in place — no changes needed

- Env-driven DB config (`DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USER`/`DB_PASSWORD`), no hardcoded
  values — `application.yml` already parameterizes all of it with sane local defaults.
- `JWT_SECRET` / `JWT_EXPIRATION_MS` / `JWT_REFRESH_EXPIRATION_MS`, `CORS_ORIGINS`,
  `RESEND_API_KEY` / `EMAIL_FROM`, `APP_BASE_URL`, `SPRING_PROFILES_ACTIVE` — all already wired.
- `application.yml` / `application-dev.yml` / `application-prod.yml` split already exists (plus
  `application-test.yml`, not in the memo's list but already there).
- CORS: already a dedicated `CorsConfigurationSource` bean, wired through
  `HttpSecurity.cors(...)` (not a competing standalone filter), reads from `CORS_ORIGINS`, no
  wildcard, `AllowCredentials=true` already set.
- `spring-boot-starter-actuator` already a dependency; `/actuator/health` already configured with
  `show-details: never` (correctly avoiding leaking DB/disk details on a public endpoint).
- Swagger/OpenAPI already disabled in the prod profile.
- Statement file storage: **not actually a "local storage" problem** — uploaded files are stored
  as `bytea` columns in Postgres (`StatementImport.fileContent`, `ImportSession.fileContent`),
  not on local disk at all. Survives redeploys fine on Railway's ephemeral filesystem as-is
  (whether Postgres-blob vs. S3/R2 is the right long-term architecture at scale is a separate,
  real question — just not the "breaks on every redeploy" bug the memo's framing implies).
- Sensitive-data logging: swept for it — the only borderline case (`NoOpEmailService` logging a
  password-reset link) is already correctly gated to only run when `RESEND_API_KEY` is unset, so
  it only ever fires in local dev, never in a real deployment.

## Real gaps found and fixed

### 1. Frontend API base URL — likely the actual reason the deployed frontend can't reach the backend

Both `frontend/src/api/client.ts` and `admin-portal/src/api/client.ts` hardcoded
`const BASE_URL = '/api/v1'` — a same-origin relative path. That only works locally because
`vite.config.ts`'s `server.proxy` forwards it to `localhost:8080` — but `server.proxy` is a
**dev-server-only** feature with zero effect on `vite build`'s actual output. Deployed as static
files to their own origin (Cloudflare Workers), `/api/v1/...` resolves against *that* origin, not
the separate Railway backend — there's no route there for it to hit. This is almost certainly why
the currently-deployed frontend can't reach the backend right now, and it's also exactly why
`CORS_ORIGINS` was already configured for cross-origin access on the backend in the first
place — that setup only makes sense if the frontend calls the backend's absolute origin directly.

**Fixed in both apps:** `BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api/v1'` — unset
(local dev) keeps working exactly as before via the Vite proxy; set (any real deployment) points
straight at the backend's own origin. Added the type declaration to both `vite-env.d.ts` files and
documented the new var in both `.env.example` files.

**You still need to set `VITE_API_BASE_URL`** to your actual Railway backend's public URL
(something like `https://confident-wonder.up.railway.app`) wherever Cloudflare Workers picks up
build-time env vars for this project — that's a dashboard/deploy-config step I can't do for you.

### 2. Production error handling — was leaking raw exception messages

`GlobalExceptionHandler`'s catch-all was returning `"Unexpected error: " + ex.getMessage()` in
every profile, unconditionally. Not a full stack trace, but a real information-disclosure risk
for a financial API regardless — a SQL exception's message can carry table/column/constraint
names, a file-path exception can carry server filesystem layout, etc. Fixed: the full exception
is now always logged server-side (correlation-ID-tagged, nothing lost for debugging), and the
raw message is only included in the client-facing response outside the `prod` profile.

### 3. Missing `.gitignore` — with a real secret currently unprotected

Checked: this repo has no `.gitignore` anywhere (root or either frontend), despite `node_modules/`
and `backend/target/` already existing as real build output, and — the concerning part —
`.finora/installation.key` (a real bootstrap secret, written by `BootstrapService`) sitting in
the repo root right now with nothing stopping it from being committed. Added a root `.gitignore`
covering all three sub-projects' build output, both frontends' env files (keeping `.env.example`
tracked), and `.finora/` specifically.

## Noted, not fixed — real but out of scope for this pass

The admin portal currently fails `tsc -b` with ~25 pre-existing errors (test-mock objects not
matching `AdminAuthState`'s real shape, a couple of filter-value types not satisfying a
`Record<string, string>` constraint, `PagedResponse<AuditLogDto>` test mocks typed as
`unknown[]`). None of them touch either file I changed here — confirmed unrelated to today's
edits. Worth a dedicated pass, but well beyond "review this deployment memo."

## Not addressed — legitimately future work, per the memo's own phasing

R2/S3 migration, Redis, Sentry/OpenTelemetry, custom domains — all correctly marked "Future" in
the memo itself; nothing to gap-check yet since none of it exists to compare against.

## Verification

`tsc -b` passes clean on both `frontend/` and `admin-portal/` *for the files touched here* (the
admin portal's pre-existing unrelated errors are still present, as noted above — that's the repo's
existing state, not a regression). Added `GlobalExceptionHandlerTest.java` (no test file existed
for this class before) — 4 tests covering prod withholding the message, dev/no-profile still
including it for developer convenience, and the 500/`INTERNAL_ERROR` shape itself. Couldn't run
`mvn test` myself (no Maven in this sandbox, same constraint as every round this session) —
please run it.
