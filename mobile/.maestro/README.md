# Mobile E2E (Maestro)

Three flows, deliberately narrow: **Login**, **Dashboard**, **Import** — the same three the web
`e2e/` suite's smoke job treats as the build-confidence gate, not the milestone brief's full
checklist (that stays a manual device-validation pass; see
`docs/engineering/mobile/mobile-setup.md`'s "Device validation checklist"). Runs nightly plus
`workflow_dispatch`, not on every PR — see `.github/workflows/maestro-nightly.yml`'s header for why,
which is the same reasoning `e2e-nightly.yml` gives for the web suite.

## Why this needs more setup than the web e2e suite

The web suite drives a dev server; Playwright never has to produce an installable artifact. Mobile
does — Maestro drives a real APK on a real (emulated) device, and getting one that boots, reaches a
backend, and signs in required resolving three things nobody had actually finished before:

1. **A native Android build that succeeds at all.** `docs/engineering/mobile/mobile-setup.md`'s
   "Android validation status" table recorded `expo prebuild` and an emulator boot as passing, a
   JDK 25 Gradle build failing on a CMake/toolchain issue, and a JDK 21 attempt "in progress" with
   no recorded result — this is the first time that JDK 21 build, and the app itself, has completed
   and installed anywhere. Use **JDK 21** for the Android Gradle build specifically (`JAVA_HOME_21_X64`
   below), not whatever JDK the backend job uses — JDK 25 fails here as recorded.

2. **A self-contained APK, not the dev-client shell.** `./gradlew assembleDebug` produces the
   `expo-dev-client` wrapper, which shows its own "Development servers / npx expo start" screen and
   never reaches this app's UI at all without a live Metro connection — there is no in-app content
   to assert on. `./gradlew assembleRelease` embeds the JS bundle at build time instead (Metro runs
   as a Gradle task, `createBundleReleaseJsAndAssets`), producing something Maestro can install and
   drive with no dev server involved. The generated `android/app/build.gradle` already points the
   `release` build type at the debug keystore (`signingConfig signingConfigs.debug`), so this needs
   no real signing key — verified by building it, not assumed from the file.

3. **Two build-time values a release build has to have baked in, that a `expo start` dev workflow
   never needs to think about**, both required at `expo prebuild` time (regenerate `android/` after
   changing either):
   - `EXPO_PUBLIC_API_BASE_URL` — `mobile/src/api/client.ts` throws at import time if this is
     unset, same as the `Bundle (Metro)` step in `ci.yml`'s `mobile` job already works around. Point
     it at `http://10.0.2.2:<port>` — the Android emulator's fixed alias for the host machine's
     `localhost` — not `localhost` itself, which inside the emulator means the emulator.
   - `MAESTRO_ALLOW_CLEARTEXT=true` — Android blocks plaintext HTTP for any app targeting API 28+
     by default, which is correct for every real build (production and staging are both HTTPS) and
     wrong for exactly this one: a local/CI backend at `http://10.0.2.2` has no certificate to
     terminate TLS with. See `app.config.ts`'s `expo-build-properties` block for the env-gated
     `usesCleartextTraffic` override — unset (Expo's default) unless this flag is explicitly true, so
     it can't leak into a real build path by accident.

## Running locally

```bash
# 1. Backend + Postgres (any local instance works; see docker-compose.yml, or run the jar directly
#    as ci.yml's smoke job does). Needs a Firebase-credentials-shaped file for FirebaseConfig to
#    initialize -- see that job's "Production classpath check" step for a throwaway one, or just
#    accept that phone-OTP-adjacent code paths (never exercised by these 3 flows) will 503.

# 2. Seed the one fixed test account these flows sign in as:
MAESTRO_API_ORIGIN=http://localhost:18090 \
MAESTRO_DB_URL=postgresql://finora:finora@localhost:5434/finora \
  ./mobile/.maestro/seed-test-user.sh

# 3. Build a self-contained release APK against that backend. MAESTRO_ALLOW_CLEARTEXT has to be
#    set on prebuild, not on the gradle step -- it gates a config plugin that only runs during
#    prebuild and bakes usesCleartextTraffic into the generated manifest once (see app.config.ts).
#    EXPO_PUBLIC_API_BASE_URL is the opposite: read by client.ts, so it only matters when Metro
#    bundles the JS, which happens as part of the gradle step, not prebuild.
cd mobile
GOOGLE_SERVICES_JSON="$(pwd)/.maestro/google-services.placeholder.json" MAESTRO_ALLOW_CLEARTEXT=true \
  npx expo prebuild -p android --clean
EXPO_PUBLIC_API_BASE_URL=http://10.0.2.2:18090 JAVA_HOME=<your JDK 21 home> \
  ./android/gradlew -p android assembleRelease

# 4. Boot an emulator (this repo's own AVD, if you have Android Studio installed, is named
#    Pixel_10), install the APK, and push the CSV fixture:
adb install -r mobile/android/app/build/outputs/apk/release/app-release.apk
adb push mobile/.maestro/fixtures/maestro-statement.csv /sdcard/Download/maestro-statement.csv

# 5. Run a flow:
maestro test mobile/.maestro/flows/login.yaml
```

## The placeholder Firebase config

`google-services.placeholder.json` is committed, not gitignored like a real `google-services.json`
(see `mobile/.gitignore` and `app.config.ts`'s own header comment on why a real one never is) —
because it isn't one. `@react-native-firebase/app`'s config plugin fails `expo prebuild` outright
if the file it's pointed at is *missing*, but only checks that it's *present and correctly shaped*
(matching `package_name: com.finoratech.app`) to let the native build proceed — it never validates
the project behind it is real. None of these three flows touch Firebase at all: email/password
login is Finora's own backend JWT (see `mobile/src/context/AuthContext.tsx`), and phone
verification is bypassed by `seed-test-user.sh` the same way `e2e/fixtures/accounts.ts` bypasses it
for the Playwright suite. A fake project is sufficient because nothing here ever calls it.

## The Import upload bug

Writing `flows/import.yaml` found a real, previously-undiscovered bug, not a flow-authoring
mistake: the *first* upload attempt right after `expo-document-picker`'s system file picker
returns control to the app could fail instantly with axios's `ERR_NETWORK` ("Network Error", no
response reached the client at all), even though the picked file was confirmed present on disk and
every other request from the same screen — before or after — succeeded. Two plausible causes were
investigated and ruled out first (both looked promising, neither reproduced against real evidence):

- A malformed multipart body, because `stageCsv`/`stagePdf` set `Content-Type: multipart/form-data`
  by hand with no boundary — genuinely invalid HTTP regardless, so left removed, but proven NOT to
  be the cause of this specific failure by reproducing it identically with the header removed.
- axios 1.19 failing to recognize React Native's `FormData` for its request-transform pipeline,
  because the observed `Content-Type` on the failed request was axios's url-encoded fallback, not a
  multipart one — also proven NOT the cause by reproducing the identical failure with
  `transformRequest` forced to a pass-through no-op.

The actual cause, confirmed by adding a fixed delay before the upload and watching the failure
disappear, then finding the real signal in `adb logcat`: a `ConnectivityService: RemoteException`
for this app's own request package, timed exactly at the document-picker activity handoff — the
OS's network-callback delivery to a briefly-backgrounded app process, not anything about the
request body. `mobile/src/api/endpoints.ts`'s `stageWithRetry` fixes it properly: one retry, gated
on `isOffline()` (a genuine transport failure, not a slow response or a server error), scoped to
only these two call sites — not a blind delay before every upload, and not a global retry policy
on the whole `api` client.

## Why the Import flow isn't perfectly repeatable

`flows/import.yaml` reuses the same seeded account as the other two flows rather than registering a
fresh one — Maestro flows are static YAML with no scripting hook to mint a random email per run the
way `e2e/fixtures/accounts.ts`'s `createUser()` does for Playwright. A second run against a database
that already has this account's transactions in it exercises the review step's duplicate-detection
path instead of a clean first import — a different code path, not a broken flow, but worth knowing
if a rerun's screenshots look different from the first. Rerunning locally against a long-lived
database needs BOTH the transactions and the `import_sessions` row for the same file content
cleared (that table is a staging-idempotency cache -- V79's migration name says as much -- so a
restage of byte-identical content returns the FIRST run's staging result, duplicate flags and all,
regardless of what's since happened to the underlying transactions). CI seeds a fresh `services:`
Postgres container per run, so none of this applies there.

## What Maestro flows can't tell you

DashboardScreen's KPI cards and section headings are asserted on because they're always present
once the query resolves — a freshly seeded account has an empty ledger, so this suite deliberately
does not assert on any figure. Financial correctness (does an imported transaction change the
balance by the right amount) is exactly what the *web* e2e suite's DB-cross-check assertions exist
for (`e2e/fixtures/db.ts`'s own doc comment) — nothing here duplicates that.
