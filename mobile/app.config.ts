import { existsSync } from 'fs';
import path from 'path';
import { withSentry } from '@sentry/react-native/expo';
import type { ExpoConfig } from 'expo/config';

// Dynamic config (not app.json) so bundle identifiers and Firebase config-file paths are defined
// in one typed place. Requires the Expo "dev client" / EAS Build workflow, not Expo Go — see
// @react-native-firebase/app and @react-native-firebase/auth in the plugins list below, both of
// which need native code Expo Go can't load (see the mobile roadmap for why).
//
// GoogleService-Info.plist / google-services.json are NOT committed to the repo (gitignored, same
// treatment as the backend's GOOGLE_APPLICATION_CREDENTIALS in
// docs/engineering/deployment-guide.md) — every developer downloads their own copy from the
// Firebase Console, for the same Firebase project the backend already uses, and drops it in this
// directory before building.
//
// Each googleServicesFile key is only emitted when the file is actually present. Pointing the key
// at a missing path makes every Expo CLI command print a "Could not parse Expo config" error,
// which would greet every fresh clone before they've had a chance to download credentials.
//
// What omitting it buys is narrower than it looks: the JS-only commands (`expo start`, `expo
// export`, the CI bundle job) work on a fresh clone. A NATIVE build does not. `expo prebuild` and
// `expo run:android`/`run:ios` fail in @react-native-firebase's own config plugin with "Path to
// google-services.json is not defined", because that plugin requires the key regardless of what
// this file does. Verified, not assumed. See mobile-setup.md's "An iOS build error that points at
// the wrong thing", which documents the same failure and its fix: download the file.
/**
 * On EAS the file arrives as a secret FILE environment variable rather than in the uploaded source,
 * because it is gitignored and so is never part of what EAS receives. EAS materialises the upload
 * into the build workspace and sets these variables to its absolute path.
 *
 * This is why the first cloud build failed in the Prebuild phase: `app.config.ts` omits
 * `googleServicesFile` when the file is absent, and @react-native-firebase's plugin then rejects
 * the config -- reporting it as a missing app.json key, several steps from the real cause. Reading
 * the env var first keeps the credential out of git while still giving the cloud build a real path.
 *
 * Manage with `eas env:list` / `eas env:create --type file`.
 */
/**
 * Which build this is. Set per profile in eas.json; unset means production.
 *
 * Production is the default deliberately, so anything that resolves this config without opting in
 * -- `expo config`, a local gradle build, the Maestro flows, CI -- keeps the identifiers it had
 * before variants existed. A dev build is the thing you ask for; it is never what you get by
 * forgetting to ask.
 */
const isDev = process.env.APP_VARIANT === 'development';

/**
 * Dev and production are separate Firebase projects, so they are separate config files. The
 * `.dev` copies are gitignored alongside the production ones -- same rule, same reason.
 */
const iosGoogleServices =
  process.env.GOOGLE_SERVICES_PLIST ?? (isDev ? './GoogleService-Info.dev.plist' : './GoogleService-Info.plist');
const androidGoogleServices =
  process.env.GOOGLE_SERVICES_JSON ?? (isDev ? './google-services.dev.json' : './google-services.json');
// An EAS-provided path is already absolute; only a repo-relative default needs resolving.
const here = (p: string) => (path.isAbsolute(p) ? p : path.join(__dirname, p));

const config: ExpoConfig = {
  // Distinct on the home screen, because the whole point of the dev variant is that both are
  // installed at once and you have to be able to tell which one you just tapped.
  name: isDev ? 'Fynora Dev' : 'Fynora',
  // NOT varied: the slug identifies the EAS project, which is shared. Two variants of one app,
  // not two apps.
  slug: 'finora-mobile',
  version: '1.0.0',
  orientation: 'portrait',
  icon: './assets/icon.png',
  userInterfaceStyle: 'automatic',
  // Varied for the same reason as the identifier: with both installed, a shared scheme makes
  // which app handles a deep link a coin toss the OS resolves however it likes.
  scheme: isDev ? 'finora-dev' : 'finora',
  ios: {
    supportsTablet: true,
    // Third identifier, and the last one that can be changed for free. 'com.finora.app' was the
    // original and is unavailable under this project's Apple Developer team ("not available" --
    // either a third-party collision or residue from the Organization/DUNS enrollment abandoned in
    // favor of Individual, per D-14/R-16). It was then renamed to 'com.finoratech.app' after the
    // domain the backend and frontend used at the time. That domain has since been sold and cut
    // over, so the identifier pointed at something this project no longer owns while the app was
    // shipping under the name Fynora.
    //
    // A bundle identifier is effectively permanent once an app has been submitted: changing it
    // afterwards means a new App Store listing and a new Play listing, with ratings, reviews and
    // installs starting from zero. Nothing has been submitted yet, so this is the free moment, and
    // D-31 in the plan doc records the decision to spend it rather than let first submission make
    // it by default. Android's package matches deliberately -- one identifier across both
    // platforms rather than a permanent divergence nobody remembers is there.
    //
    // NOT self-contained. The backend verifies Apple ID tokens against this exact string (Apple's
    // `aud` claim on a natively-minted token IS the bundle identifier -- see AppleLoginProperties),
    // and Firebase registers phone auth, Play Integrity and the native Google OAuth clients against
    // it. See docs/engineering/mobile/mobile-setup.md, "Bundle identifier migration", for the
    // console and environment steps this line does not perform.
    //
    // The `.dev` suffix exists because Google resolves an OAuth client from the (package, SHA-1)
    // pair, and that pair has to be unique across Firebase projects. Registering com.fynora.app in
    // both the production and dev projects did not merely warn -- Firebase declined to create the
    // dev project's Android OAuth clients at all, and the downloaded google-services.json came back
    // with no client_type 1 entry and no certificate_hash. Re-registering under com.fynora.app.dev
    // produced both immediately. See mobile-setup.md, "Dev and production variants".
    bundleIdentifier: isDev ? 'com.fynora.app.dev' : 'com.fynora.app',
    ...(existsSync(here(iosGoogleServices)) ? { googleServicesFile: iosGoogleServices } : {}),
  },
  android: {
    // Matches ios.bundleIdentifier above -- see its comment for the history and the migration.
    package: isDev ? 'com.fynora.app.dev' : 'com.fynora.app',
    ...(existsSync(here(androidGoogleServices)) ? { googleServicesFile: androidGoogleServices } : {}),
    // Adaptive icon: a solid graphite plate with the Finora mark as the foreground layer.
    //
    // `backgroundColor` and NO `backgroundImage`, deliberately. Expo passes a supplied
    // backgroundImage straight through to Android, where it WINS over backgroundColor -- and the
    // image previously named here was `android-icon-background.png`, the pale blue grid from the
    // Expo template. So the colour on this line was decorative: every Android launcher was drawing
    // the template's background, not ours. Dropping the key is what makes the colour take effect,
    // which is why the file it pointed at is deleted rather than left in place unused.
    //
    // #262A33 is the same graphite icon.png/the foreground layer are drawn on -- see
    // frontend/src/index.css's --color-primary for the web app's identical value -- so the
    // foreground meets the background with no visible seam when a launcher masks the icon to a
    // circle. (Previously #020E32, sampled from the pre-rebrand mark's own navy plate.)
    //
    // foregroundImage is inset to ~52% of its canvas on purpose. Android reserves the outer third
    // for mask and parallax, guaranteeing only the inner 66% is visible, so artwork drawn to the
    // edge loses its extremities to whatever shape the launcher picks.
    adaptiveIcon: {
      backgroundColor: '#262A33',
      foregroundImage: './assets/android-icon-foreground.png',
      monochromeImage: './assets/android-icon-monochrome.png',
    },
    predictiveBackGestureEnabled: false,
    // Task 14. Android 13 (API 33) made this a runtime permission (src/lib/pushRegistration.ts
    // requests it at the moment push registration actually needs it, via PermissionsAndroid --
    // below API 33 the OS grants it implicitly and that call is skipped entirely) -- but a runtime
    // permission can only be requested if it's declared in the manifest first. Neither
    // @react-native-firebase/messaging's own config plugin nor the underlying Android SDK adds
    // this permission on its own (checked: no occurrence of POST_NOTIFICATIONS anywhere under
    // node_modules/@react-native-firebase/messaging), so it has to be listed explicitly here --
    // Expo merges `android.permissions` into the generated AndroidManifest.xml at prebuild time.
    permissions: ['android.permission.POST_NOTIFICATIONS'],
  },
  web: {
    favicon: './assets/favicon.png',
  },
  // Links this checkout to the EAS project @siddharth705/finora-mobile. `eas init` normally writes
  // this itself, but it refuses to edit a dynamic config (app.config.ts) and prints the block to
  // paste instead -- hence it living here by hand rather than by tooling.
  //
  // Not a secret: an EAS project id identifies a project, it does not authorise anything. Builds
  // still require an authenticated `eas` session, so this is safe to commit and has to be, or every
  // fresh clone would need to re-link before it could build.
  extra: {
    eas: {
      projectId: '26326587-eec7-4917-a1ab-5a2390f41714',
    },
  },
  plugins: [
    'expo-secure-store',
    'expo-sharing',
    // No options, deliberately: this project has never set a custom splash image (no `"splash"`
    // key existed before this either), so the plugin keeps generating the same icon-derived
    // default it always has. Only reason it's listed at all is that App.tsx now calls
    // SplashScreen.preventAutoHideAsync()/hideAsync() at runtime -- see App.tsx's own comment --
    // and Expo's installer requires the plugin registered for that runtime API to be present.
    'expo-splash-screen',
    // Same reasoning as expo-splash-screen just above: no build-time options, listed only because
    // App.tsx calls useFonts() (from expo-font) at runtime and Expo's installer requires the
    // plugin registered for that.
    'expo-font',
    '@react-native-community/datetimepicker',
    '@react-native-firebase/app',
    '@react-native-firebase/auth',
    // Task 14. Same family, same major version (26.x, pinned exactly to match whatever
    // @react-native-firebase/app resolves to -- see package.json) so it shares the app/auth
    // pair's already-initialised FirebaseApp instance rather than standing up a second one.
    '@react-native-firebase/messaging',
    './plugins/withRNFirebaseDisableSPM',
    // D-23 Phase 2. Called with NO options, deliberately: given options, this plugin wants a raw
    // `iosUrlScheme` (Google's "reversed client id" for a NON-Firebase-registered OAuth client) --
    // called bare like this, it instead reads Google Sign-In's iOS URL scheme and Android OAuth
    // client straight out of the SAME GoogleService-Info.plist / google-services.json this project
    // already downloads per-developer for @react-native-firebase/auth's phone-OTP flow (see the
    // conditional googleServicesFile keys on ios/android above) -- one set of credentials, one
    // download step, not a second gitignored-file convention to document and keep in sync.
    // Requires the Firebase project's own "Google" sign-in provider to actually be enabled (not
    // yet, as of this writing -- see GoogleLoginProperties' own doc comment on the equivalent
    // backend-side unconfigured state); until then this plugin still runs, it just has nothing new
    // to read out of the config files.
    '@react-native-google-signin/google-signin',
    // D-23 Phase 2 / D-26. Adds the "Sign In with Apple" iOS entitlement -- no options, no
    // credentials needed at build time (Apple's own private key/Services ID only matter to the
    // BACKEND verifier, at sign-in time, not to this entitlement). Safe to list unconditionally.
    'expo-apple-authentication',
    // SEC-09 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). Optional app-lock
    // (Settings > Security) -- faceIDPermission supplies iOS's required NSFaceIDUsageDescription;
    // Android's biometric prompt needs no equivalent build-time config. See src/lib/appLock.ts.
    [
      'expo-local-authentication',
      { faceIDPermission: 'Allow Fynora to use Face ID to unlock the app.' },
    ],
    [
      'expo-build-properties',
      {
        ios: {
          useFrameworks: 'static',
          // Required so RNFBApp/RNFBAuth/RNFBMessaging link correctly under static frameworks —
          // see rnfirebase.io's Expo config-plugin install guide. RNFBMessaging added for Task 14.
          forceStaticLinking: ['RNFBApp', 'RNFBAuth', 'RNFBMessaging'],
        },
        android: {
          // Android blocks cleartext (plain HTTP) traffic by default for any app targeting API 28+,
          // which every real build of this app correctly leaves alone — production and staging are
          // both HTTPS. The one build that needs it is a Maestro run against a local/CI backend at
          // http://10.0.2.2:<port> (the emulator's alias for the host), which has no certificate to
          // terminate TLS with. MAESTRO_ALLOW_CLEARTEXT is unset for every other build path (a
          // developer's `expo run:android`, any `eas build` profile), so this is `undefined` --
          // Expo's own default -- everywhere except a Maestro build, which sets it explicitly. See
          // mobile/.maestro/README.md.
          usesCleartextTraffic: process.env.MAESTRO_ALLOW_CLEARTEXT === 'true' ? true : undefined,
        },
      },
    ],
  ],
};

// Sentry's plugin exists to upload source maps during an EAS build, so a crash arrives as real
// file and line numbers instead of minified bundle offsets. It needs the org and project slugs,
// which are per-account values not committed here, plus a SENTRY_AUTH_TOKEN available to the
// build. Applied only when both slugs are present so a checkout without them still builds
// normally — crash capture itself is driven by EXPO_PUBLIC_SENTRY_DSN at runtime and does not
// depend on this. See docs/engineering/mobile-setup.md.
const sentryOrg = process.env.SENTRY_ORG;
const sentryProject = process.env.SENTRY_PROJECT;

export default sentryOrg && sentryProject
  ? withSentry(config, { organization: sentryOrg, project: sentryProject })
  : config;
