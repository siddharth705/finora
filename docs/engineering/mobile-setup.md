# Mobile Setup & Device Validation

Everything needed to get `mobile/` running on real hardware, and the checklist to validate it once
it is. Written for the Mobile Readiness milestone — the phases shipped so far are verified only by
type-check, Metro bundle, and logic tests, and **no screen has ever rendered on a device**.

Ordered so the parts with external lead time start first, and so the work that needs no paid
account isn't blocked behind the work that does.

## Contents

1. [Already done in the repo](#already-done-in-the-repo)
2. [Track A — Android, startable today](#track-a--android-startable-today)
   - [Building locally instead of on EAS](#building-locally-instead-of-on-eas)
3. [Track B — iOS, gated on Apple enrollment](#track-b--ios-gated-on-apple-enrollment)
4. [Firebase phone auth on device](#firebase-phone-auth-on-device)
5. [Device validation checklist](#device-validation-checklist)
6. [Known limitations](#known-limitations)

---

## Already done in the repo

Don't redo these:

- `mobile/` Expo workspace with `expo-dev-client` installed.
- `@react-native-firebase/app` + `/auth` installed and registered as config plugins in
  `mobile/app.config.ts`, with `expo-build-properties` set to `useFrameworks: static` and
  `forceStaticLinking: ['RNFBApp', 'RNFBAuth']` (required for RNFirebase on iOS).
- Bundle identifiers set to `com.finora.app` on **both** platforms (`ios.bundleIdentifier`,
  `android.package`). **Confirm this before the first store submission — it is effectively
  permanent afterwards.**
- `mobile/eas.json` with `development`, `preview`, and `production` profiles.
- `app.config.ts` references `GoogleService-Info.plist` / `google-services.json` only when those
  files are actually present, so commands don't error before you've downloaded them.

## Track A — Android, startable today

**No paid account required.** A Google Play Developer account ($25, one-time) is needed only to
*distribute* through the Play Store, not to build or install on your own device. All you need is a
free Expo account.

```bash
npm install --global eas-cli
```

```bash
eas login
```

Then, from `mobile/`:

```bash
eas build --profile development --platform android
```

EAS builds in the cloud and gives you an install URL / QR code. Install the APK on the device, then
start the bundler and connect to it:

```bash
cd mobile && npx expo start --dev-client
```

The dev client is a custom build of the app that includes the native modules Expo Go can't load —
which is why Expo Go is not an option for this project at all (see `@react-native-firebase/auth`).
You only rebuild the dev client when native dependencies change; ordinary JS edits reload over the
bundler like normal.

### Building locally instead of on EAS

The EAS path above needs nothing installed beyond `eas-cli` — the toolchain lives in the cloud.
Building on your own machine (`npx expo run:android`, or `expo prebuild` + `./gradlew
assembleDebug`) needs four things a stock Android Studio install does **not** all give you.

> **How far this was verified.** Each requirement below was hit, diagnosed and cleared on a real
> Windows 11 machine, in this order — the build got past every one of them. It was then stopped
> during native compilation for reasons of time, so **no APK was produced and nothing has been
> installed on a device**. Treat the prerequisites as confirmed and the "it builds end to end" claim
> as untested. Nothing after the native-compile stage is covered here.

Each one costs a 13–22 minute build to discover, because the failure comes from whichever Gradle
task happens to reach it first — not from anything that names the real cause.

| Requirement | Why | Install |
|---|---|---|
| `cmdline-tools` | Provides `sdkmanager` and `avdmanager`. Android Studio can install the SDK without these, leaving no way to add packages or create an emulator from the shell. | SDK Manager ▸ SDK Tools ▸ "Android SDK Command-line Tools", or unzip Google's `commandlinetools-*` into `$ANDROID_HOME/cmdline-tools/latest` |
| Accepted SDK licenses | AGP auto-installs missing SDK pieces mid-build, but only for packages whose licence has been accepted. Unaccepted ones fail the build instead. | `yes \| sdkmanager --licenses` |
| **NDK 27.1.12297006** | React Native compiles native code. The version is pinned — a different NDK does not satisfy it. AGP's own attempt to fetch it is what fails. | `sdkmanager "ndk;27.1.12297006"` |
| **A non-GraalVM JDK 17 or 21** | AGP's `JdkImageTransform` shells out to `jlink`, and GraalVM's `jlink` fails it. Temurin works. | Point Gradle at it explicitly (below) |

Build platforms (`platforms;android-36` and friends) are *not* on this list: AGP downloads those
itself once licences are accepted. The NDK is the one it could not.

The NDK error names the wrong culprit — it surfaces as a React Native plugin failure:

```
> Failed to apply plugin 'com.facebook.react.rootproject'.
   > com.android.builder.sdk.InstallFailedException: Failed to install the following
     SDK components: ndk;27.1.12297006
```

**Nothing is wrong with React Native or the Gradle files.** The NDK is simply absent, and
`assembleDebug` cannot install it for you.

The JDK one is worse, because Gradle does not use whatever `java -version` reports — it uses the
JDK it discovers, which may be one you forgot you had:

```
> Execution failed for JdkImageTransform: …/platforms/android-36/core-for-system-modules.jar
   > Error while executing process …\.jdks\graalvm-jdk-17.0.11\bin\jlink.exe
```

Read the path in that message before changing anything else — it names the JDK actually in use, and
it is frequently one you forgot was installed. Gradle **auto-detects** JDKs (`~/.jdks`, Android
Studio's bundled one, package managers) and picks one for the toolchain, so this is not decided by
`JAVA_HOME` or by whatever `java -version` prints.

`-Dorg.gradle.java.home=…` alone does **not** fix it — verified: the transform still ran GraalVM's
`jlink`, because that comes from toolchain resolution rather than the daemon's own JVM. Auto-detection
has to be turned off as well:

```bash
./gradlew assembleDebug -Dorg.gradle.java.home="<jdk>" -Dorg.gradle.java.installations.auto-detect=false -Dorg.gradle.java.installations.paths="<jdk>"
```

To make it stick, put those three in `~/.gradle/gradle.properties` — user-level, not
`mobile/android/gradle.properties`, which `expo prebuild` regenerates and `.gitignore` excludes, so
anything written there is erased by the next prebuild. Be aware that
`auto-detect=false` is global to your Gradle installs; scope it per-project if other builds on the
machine rely on toolchain detection.

#### Running on an emulator

Any recent x86_64 system image works. The one this was verified against:

```bash
avdmanager create avd -n finora-test -k "system-images;android-37.1;google_apis_playstore_ps16k;x86_64" --device pixel_7
```

```bash
emulator -avd finora-test -no-snapshot -no-boot-anim
```

Cold boot takes a few minutes. `adb devices` reports `offline` until it finishes — wait for
`adb shell getprop sys.boot_completed` to return `1` rather than trusting the device list.

Then set `EXPO_PUBLIC_API_BASE_URL=http://10.0.2.2:8080` in `mobile/.env.local`. **`10.0.2.2` is
the emulator's alias for the host machine's loopback** — `localhost` inside the emulator is the
emulator itself. This is the same trap as the physical-device section below, with a different
answer. Bridge Metro in with `adb reverse tcp:8081 tcp:8081`.

A local build also still needs `google-services.json` present — see
[Firebase Android app](#firebase-android-app). `expo prebuild` fails without it regardless of
whether the build would otherwise succeed.

#### Expect the first build to be slow, and know which knob shortens it

`mobile/android/gradle.properties` (generated by prebuild) carries:

```
reactNativeArchitectures=armeabi-v7a,arm64-v8a,x86,x86_64
```

**Native code is compiled once per architecture — four times.** In an observed run this dominated
everything else: Java and Kotlin compilation finished inside the first ~20 minutes, and the
remainder was `buildCMakeDebug` tasks working through the ABI list one at a time, several
`clang++` processes at a go.

An emulator needs exactly one of those (`x86_64` for a standard x86 AVD; `arm64-v8a` on Apple
silicon). For local development you can cut the list down:

```
reactNativeArchitectures=x86_64
```

This has **not** been measured here — the reasoning is simply that three quarters of the native
work is for hardware you are not testing on. Keep all four for anything you ship. Because prebuild
regenerates this file, set it after prebuild or via `-PreactNativeArchitectures=x86_64` on the
Gradle command line.

Budget accordingly: this is a **multi-gigabyte, tens-of-minutes** first build. Roughly 3.5 GB of
resident memory went to the Gradle daemon, the Kotlin daemon and parallel `clang++` workers, before
counting an emulator (~2 GB), Metro, and a local backend. On a 16 GB machine, running all of those
at once starved the emulator badly enough that Android's own System UI stopped responding. Run the
build first, then start the emulator, then the backend and Metro — not concurrently.

### `EXPO_PUBLIC_API_BASE_URL` on a real device — read this before your first run

**`http://localhost:8080` will not work.** On a physical device, `localhost` is the *phone*, not
your machine, so every request fails with a confusing network error rather than anything that
points at the cause. `eas.json` deliberately sets no value for this — a missing one makes
`src/api/client.ts` throw a descriptive error at startup, which is a much better failure than
silently pointing at the wrong host.

For a dev client the JS comes from your local Metro bundler at runtime, so `mobile/.env.local` is
what applies. Set it to something the device can actually reach:

- **Your machine's LAN IP** — e.g. `EXPO_PUBLIC_API_BASE_URL=http://192.168.1.42:8080`. Phone and
  computer must be on the same network, and the backend must bind `0.0.0.0` rather than loopback.
- **The deployed Railway backend** — simplest, works from any network, and exercises the same CORS
  and TLS path production uses.

For `preview` and `production` builds the bundle is created on EAS, so the value must come from
either an `env` block in that profile or EAS environment variables in the Expo dashboard. It is
intentionally not committed — the backend URL is per-deployment, and a placeholder that looks real
is worse than none.

### Firebase Android app

Needed before phone verification will work. In the Firebase Console, on **the same project the
backend's `GOOGLE_APPLICATION_CREDENTIALS` service account belongs to**:

1. Project Settings → Your apps → Add app → Android.
2. Package name: `com.finora.app` (must match `android.package` exactly).
3. Download `google-services.json` → place it at `mobile/google-services.json`.
   It is gitignored on purpose — same rule as the backend's service-account key. Every developer
   downloads their own.
4. After the first EAS build, get the signing fingerprints:

```bash
eas credentials --platform android
```

5. Add both the SHA-1 and SHA-256 to Firebase Console → Project Settings → Your apps → the Android
   app → Add fingerprint. **Phone auth fails on Android without this** — Play Integrity uses it to
   attest the app. This is server-side config, so no rebuild is needed after adding it.

## Track B — iOS, gated on Apple enrollment

**Requires the Apple Developer Program ($99/yr).** EAS needs it to provision your device — there is
no free path through EAS. Enrollment involves identity verification and can take more than a day,
so **start it now even though iOS validation comes later.**

Once enrolled:

```bash
eas build --profile development --platform ios
```

EAS will ask to register the test device (it generates a provisioning profile and walks you through
installing it).

### Firebase iOS app

1. Firebase Console → Project Settings → Your apps → Add app → iOS.
2. Bundle ID: `com.finora.app` (must match `ios.bundleIdentifier`).
3. Download `GoogleService-Info.plist` → place at `mobile/GoogleService-Info.plist` (also
   gitignored).
4. **APNs key** — this is the step most easily missed, and phone auth silently fails without it.
   iOS Firebase phone auth verifies the app via a silent push notification rather than reCAPTCHA:
   - Apple Developer → Certificates, Identifiers & Profiles → Keys → create a key with **Apple Push
     Notifications service (APNs)** enabled. Download the `.p8` (you can only download it once).
   - Firebase Console → Project Settings → Cloud Messaging → the iOS app → upload the `.p8`, with
     its Key ID and your Team ID.

## Firebase phone auth on device

Regardless of platform, add test numbers so validation doesn't depend on real SMS delivery or burn
quota:

Firebase Console → Authentication → Sign-in method → Phone → **Phone numbers for testing**. Add a
number and a fixed 6-digit code. Signing in with that number accepts that code without sending
anything. Use these for repeat runs and anything automated; use one real number at least once to
confirm actual delivery works.

## Device validation checklist

Run on **one real Android device and one real iPhone**. The simulator is not sufficient for the
auth flow — the iOS Simulator cannot receive SMS at all, and Play Integrity / APNs app verification
don't behave the same off-device.

### Auth
- [ ] Register a new account; verify the phone step is reached and completes.
- [ ] Paste a full `+91…` number into the phone field — confirm it lands as 10 digits, not corrupted.
      *(Fixed in Phase 1; this is the regression check.)*
- [ ] Paste a whole OTP SMS into the code field — confirm it extracts the 6 digits.
- [ ] Wrong OTP shows "That code doesn't match", expired shows the expiry message.
- [ ] Resend issues a new code.
- [ ] Sign out from the verify screen returns to Login.
- [ ] Sign in with **email**, and separately with **phone number** — both work.
- [ ] Forgot password sends the email and shows the "check your email" state.

### Session
- [ ] Force-quit and reopen while signed in — lands on the dashboard, **no flash of the Login screen**.
      *(This is what `bootstrapping` in AuthContext exists to prevent.)*
- [ ] Leave the app backgrounded past the 15-minute access-token expiry, return, and pull to refresh —
      the silent refresh should keep you signed in, not bounce you to Login.
- [ ] Sign out, force-quit, reopen — lands on Login.

### Screens
- [ ] Dashboard: KPIs, cash-flow chart, donut, recent transactions, goals, insights all render.
- [ ] Dashboard with a brand-new empty account — no charts crash on zero data.
- [ ] Ledger: scroll past the first 20 to confirm infinite scroll fetches page 2.
- [ ] Ledger: search debounces (one request after typing settles, not per keystroke).
- [ ] Ledger: type filter, pull-to-refresh, long-press delete with confirm.
- [ ] Accounts: masked number reveals, then re-masks itself after 8 seconds.
- [ ] More: profile renders, sign-out confirm works.
- [ ] Tab bar switches cleanly; no lost scroll position or remount flicker.

### Presentation
- [ ] Light and dark mode on both platforms — check contrast on chart legends and muted text.
- [ ] Small phone (e.g. iPhone SE) and a large one — KPI cards and charts don't overflow.
- [ ] Landscape, or confirm the app is portrait-locked as intended.
- [ ] Keyboard doesn't cover the field being typed into on any auth screen.
- [ ] Safe areas respected — nothing under the notch or home indicator.

### Failure behavior
- [ ] Airplane mode: errors are readable, not a raw axios message or a blank screen.
- [ ] Point `EXPO_PUBLIC_API_BASE_URL` at an unreachable host — the app degrades rather than hanging.
- [ ] Backend returns 403 `PHONE_VERIFICATION_REQUIRED` — app routes to the verify screen.

## Crash reporting (Sentry)

Wired up but inert until you supply a DSN. `src/lib/monitoring.ts` no-ops when
`EXPO_PUBLIC_SENTRY_DSN` is unset, so development and CI never emit events.

1. Create a project in Sentry (platform: React Native).
2. Project Settings → Client Keys (DSN) → copy the DSN into `mobile/.env.local` as
   `EXPO_PUBLIC_SENTRY_DSN`, and into your EAS build profile or EAS environment variables for
   preview/production builds. A DSN only permits *writing* events and ships inside every client
   bundle, so it isn't a secret in the way an API key is — but it is a real per-project value.
3. For readable stack traces, source maps must upload at build time. That needs three more values,
   available to the EAS build only:
   - `SENTRY_ORG` and `SENTRY_PROJECT` — the slugs from your Sentry URL. `app.config.ts` applies
     Sentry's config plugin only when both are set, so a checkout without them still builds.
   - `SENTRY_AUTH_TOKEN` — **this one is a real secret.** Never commit it; store it with
     `eas secret:create`.

   Without these, crashes are still captured; their stack traces just point at minified bundle
   offsets instead of source files.

**What is deliberately not sent.** Finora handles bank statements, so the default capture settings
are wrong for it and have been tightened: no PII, no request bodies, no console breadcrumbs, no
session replay, no performance tracing, and every URL stripped of its query string and identifiers
before leaving the device. The reasoning is in `src/lib/monitoring.ts`'s own comment, and the
scrubbers are covered by tests in `monitoring.test.ts` — scrubbing that silently stops working
looks exactly like scrubbing that works, so it isn't left untested.

If you ever need to widen what's captured, change it there and update those tests deliberately.
The ledger's search parameter carries whatever the user typed, and the registration request body
carries an email, a phone number, and a plaintext password.

## An iOS build error that points at the wrong thing

Running `expo prebuild`, `eas build --platform ios`, or `expo config --type introspect` without
`GoogleService-Info.plist` in `mobile/` fails with:

```
[@react-native-firebase/auth] Your app.json file is missing ios.googleServicesFile.
Please add this field.
```

**The config is fine; the file is missing.** `app.config.ts` emits that key only when the file is
actually present (so that everyday commands like `expo start` and `expo export` work on a fresh
clone), and the Firebase plugin then reports its absence as a config problem. Adding the key by
hand will not help — download the file per [Track B](#track-b--ios-gated-on-apple-enrollment).

An iOS build genuinely cannot succeed without it: the native Firebase SDK reads it at launch.
Android behaves the same way with `google-services.json`.

## Known limitations

- **CI does not build native code.** The mobile CI job type-checks and produces a Metro bundle,
  which catches broken imports and unpackageable code. It does **not** compile native code or
  produce an installable app — that needs EAS Build, and a macOS runner for iOS. Nothing in CI can
  substitute for the checklist above.
- **No local build has ever completed.** One attempt got through prebuild, the NDK, the JDK
  toolchain problem and all Java/Kotlin compilation, then was stopped partway through native
  compilation — see [Building locally instead of on EAS](#building-locally-instead-of-on-eas) for
  the prerequisites it established. What remains untested is everything past that point: dexing,
  packaging, install, and the app actually starting. Anyone picking this up starts from a warm
  Gradle cache, not from zero.
- **Push notifications are out of scope for v1.** There's no device-token registration endpoint on
  the backend. Note this is separate from the APNs key above, which exists solely so Firebase can
  verify the app during phone auth.
- **Password reset completes on the web app**, not in-app — the emailed link points at
  `APP_BASE_URL`. Deep-linking it is deferred until there's evidence the hand-off is real friction.
