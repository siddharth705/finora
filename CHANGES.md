# Priority tasks 1–8 — status

## 1. Backend config audit for localhost/hardcoded assumptions

Swept the entire backend Java source and every `application*.yml` for `localhost`/`127.0.0.1`/
hardcoded ports/URLs/paths/secrets/SMTP. Result: **clean** — every `localhost` reference
remaining is a `${VAR:localhost}`-style default fallback (correct, expected), or
`docker-compose.yml`'s own internal healthcheck (which legitimately runs inside the container
checking itself). No hardcoded SMTP anywhere — email goes through the already-abstracted
`EmailService`/`ResendEmailService`/`NoOpEmailService` trio.

## 2. Railway deployment review — found the actual critical gap

- **`server.port` was never configured anywhere, in any profile.** Spring Boot silently defaults
  to 8080 regardless. Railway assigns its own dynamic port via `$PORT` and routes traffic
  specifically to whatever port the app binds to — an app that ignores `$PORT` can build and
  "start" successfully on Railway while being completely unreachable. This is very possibly
  the actual reason nothing has worked end-to-end yet, on top of last round's frontend base-URL
  fix. Fixed: `server.port: ${PORT:8080}` in the base `application.yml` (keeps every local/Docker
  workflow byte-for-byte identical), removed the now-redundant hardcoded `8080` from
  `application-dev.yml` so it can't silently shadow the env-driven value.
- **No `railway.json` existed at all** — Railway had no way to know `/actuator/health` is the
  right health-check path (already correctly configured on the Spring side, just never told to
  Railway). Added `backend/railway.json`.
- **Graceful startup when config is missing** — went the other direction from what "missing env
  vars" usually means: the real danger here isn't a crash, it's that `JWT_SECRET`/`DB_PASSWORD`
  both have local-dev-convenience defaults, so *forgetting* to set them in a real deployment
  doesn't fail loudly — it starts up fine, protecting real user sessions/a real database with a
  publicly-known placeholder value. Added `ProductionConfigValidator` — refuses to start
  (`IllegalStateException`, logged loudly) if the `prod` profile is active and either value is
  still its dev default, or the JWT secret is under 32 characters. Does nothing outside `prod` —
  dev/CI keep working with zero setup. New test file, 6 cases.
- **Clear startup logging** — `ProductionConfigValidator` logs a clear pass/fail either way, and
  Spring's own actuator health/logging config (`logging.pattern.console` with correlation IDs)
  was already good.

## 3. CORS

Went through every sub-item explicitly:
- `CORS_ORIGINS` — already env-driven, already documented in the new deployment guide.
- `CorsConfigurationSource` — already a dedicated bean, no wildcard, already correct.
- Spring Security `.cors()` — already wired through `HttpSecurity.cors(...)`, the position Spring
  Security actually expects (not a standalone competing filter).
- Credentials support — already `AllowCredentials=true`.
- **OPTIONS/preflight + JWT compatibility** — checked this specifically, since it's a very common
  gotcha (preflight requests getting blocked by `.anyRequest().authenticated()`, with no explicit
  `permitAll()` for `OPTIONS` anywhere in `SecurityConfig`). Verified this is **already handled
  correctly**: Spring Security's own `CorsFilter` (registered via `.cors(cors ->
  cors.configurationSource(...))`) runs before the authorization filters and short-circuits any
  successfully-validated preflight request without ever reaching `JwtAuthFilter` or
  `AuthorizationFilter` — no explicit OPTIONS rule needed. Didn't add one; would've been a
  redundant, unnecessary change to something already correct.

## 4. Environment variable audit

Full table in the new `docs/engineering/deployment-guide.md` — every variable, whether it's
required in prod, its dev-only default, what it's for, and whether that default is safe to leave
as-is. Extracted directly from `application*.yml` (confirmed nothing in Java code reads an env
var that doesn't route through one of those files first).

## 5. Frontend environment audit

Already fixed last round (`VITE_API_BASE_URL` in both `frontend/` and `admin-portal/`). This
round: confirmed via the same grep sweep that neither app has any *other* hardcoded
localhost/Railway URL outside those two `client.ts` files, and documented both apps'
`.env.example`/env var needs in the new deployment guide rather than creating redundant
`.env.development`/`.env.production` files — Vite already picks the right `.env.*` file
automatically based on mode, and the *actual* production values need to live in Cloudflare's own
build-env config, not a committed file, so a template plus documentation is the honest artifact
here rather than a file with placeholder values that looks like it's meant to be filled in and
committed.

## 6. `APP_BASE_URL`

Already correctly used for password-reset link generation (`EmailConfig`/`EmailProperties`,
consumed wherever reset emails get built) — confirmed, not changed. Documented explicitly in the
env var audit table with the exact warning: leaving this at its `localhost:5173` default in a
real deployment means every generated reset/verification link points at localhost.

## 7. Production hardening — implemented, checked against what already existed first

**Already done, verified, not touched:**
- Security headers (CSP, HSTS, X-Frame-Options, Referrer-Policy, Permissions-Policy) — already
  thorough and already reflects current OWASP guidance.
- Rate limiting — already exists (`RateLimiter`/`RateLimitFilter`), already covers every endpoint
  with a real per-call abuse cost.
- Exception handling — fixed last round (no longer leaks raw exception messages in prod).
- JWT configuration — short-lived access tokens, longer revocable refresh tokens, already a
  sound design.

**Real gaps found and fixed this round:**
- **Rate limiting's own IP resolution was about to break on Railway.** `RateLimitFilter`'s own
  doc comment already flagged this as a known, deliberately-deferred risk: `getRemoteAddr()`
  returns the *proxy's* IP for every request once actually behind a reverse proxy — which Railway
  is. Undetected until now because nothing had actually been deployed behind a real proxy yet.
  Left unfixed, every user on the platform would share one shared rate-limit bucket the moment
  this goes live on Railway. Fixed: new `TRUST_PROXY_HEADERS` env var (default `false`, safe
  everywhere it isn't explicitly turned on), reads the real client IP from the first
  `X-Forwarded-For` entry only when enabled. New test file, 3 cases proving both the trusted and
  untrusted paths behave correctly and that spoofing doesn't work when trust is off.
- **Upload limits** — silently running on Spring Boot's own defaults (1MB max file size), which
  is genuinely too small for some real multi-page bank statement PDFs. Set explicitly,
  configurable (`UPLOAD_MAX_FILE_SIZE`/`UPLOAD_MAX_REQUEST_SIZE`, default 10MB).
- **Compression** — was off (framework default). Enabled for JSON responses.
- **Graceful shutdown** — wasn't configured. Railway sends SIGTERM on every deploy (this repo's
  auto-deploy-on-push-to-main makes that routine); without this, in-flight requests get their
  connections cut abruptly instead of allowed to finish. Enabled with a bounded 20s timeout.
- **DB connection pool tuning** — running entirely on HikariCP's own defaults. Set an explicit,
  deliberate pool size (configurable, default matches the old implicit default of 10 so nothing
  changes unless you choose to) and a 10s connection timeout so a briefly-unreachable database
  fails fast instead of hanging requests indefinitely.

**Not done — genuinely not applicable or out of scope:**
- Timeout configuration beyond DB connections (e.g. a global HTTP client read/connect timeout) —
  nothing in this codebase makes outbound HTTP calls to third parties that would need one (Resend/
  Twilio go through their own SDKs, which have their own reasonable defaults) — didn't invent a
  config surface for a problem that doesn't exist yet.

## 8. Documentation

New `docs/engineering/deployment-guide.md` — local dev, Docker, Railway (with the exact required
env var list), Cloudflare Workers (both frontends), and the full environment variable audit
table from item 4, all in one place.

## Verification

Validated every touched YAML file parses correctly (Python's `yaml.safe_load`, since I have no
Maven/JDK in this sandbox to actually boot the app). Everything else here is reasoned through by
hand against the actual Spring Boot/Security/Railway documented behavior, same constraint as
every round — please run `mvn test` and, ideally, an actual `mvn spring-boot:run` locally to
confirm nothing about the new `server.port`/Hikari/multipart config breaks local startup before
this goes anywhere near Railway.
