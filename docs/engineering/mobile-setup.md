# Mobile Setup & Device Validation

Everything needed to get `mobile/` running on real hardware, and the checklist to validate it once
it is. Written for the Mobile Readiness milestone — the phases shipped so far are verified only by
type-check, Metro bundle, and logic tests, and **no screen has ever rendered on a device**.

Ordered so the parts with external lead time start first, and so the work that needs no paid
account isn't blocked behind the work that does.

## Contents

1. [Already done in the repo](#already-done-in-the-repo)
2. [Track A — Android, startable today](#track-a--android-startable-today)
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

## Known limitations

- **CI does not build native code.** The mobile CI job type-checks and produces a Metro bundle,
  which catches broken imports and unpackageable code. It does **not** compile native code or
  produce an installable app — that needs EAS Build, and a macOS runner for iOS. Nothing in CI can
  substitute for the checklist above.
- **Push notifications are out of scope for v1.** There's no device-token registration endpoint on
  the backend. Note this is separate from the APNs key above, which exists solely so Firebase can
  verify the app during phone auth.
- **Password reset completes on the web app**, not in-app — the emailed link points at
  `APP_BASE_URL`. Deep-linking it is deferred until there's evidence the hand-off is real friction.
