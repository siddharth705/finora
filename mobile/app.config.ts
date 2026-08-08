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
const iosGoogleServices = './GoogleService-Info.plist';
const androidGoogleServices = './google-services.json';
const here = (p: string) => path.join(__dirname, p);

const config: ExpoConfig = {
  name: 'Finora',
  slug: 'finora-mobile',
  version: '1.0.0',
  orientation: 'portrait',
  icon: './assets/icon.png',
  userInterfaceStyle: 'automatic',
  scheme: 'finora',
  ios: {
    supportsTablet: true,
    bundleIdentifier: 'com.finora.app',
    ...(existsSync(here(iosGoogleServices)) ? { googleServicesFile: iosGoogleServices } : {}),
  },
  android: {
    package: 'com.finora.app',
    ...(existsSync(here(androidGoogleServices)) ? { googleServicesFile: androidGoogleServices } : {}),
    // Adaptive icon: a solid brand-navy plate with the Finora mark as the foreground layer.
    //
    // `backgroundColor` and NO `backgroundImage`, deliberately. Expo passes a supplied
    // backgroundImage straight through to Android, where it WINS over backgroundColor -- and the
    // image previously named here was `android-icon-background.png`, the pale blue grid from the
    // Expo template. So the colour on this line was decorative: every Android launcher was drawing
    // the template's background, not ours. Dropping the key is what makes the colour take effect,
    // which is why the file it pointed at is deleted rather than left in place unused.
    //
    // #020E32 is sampled from the mark's own navy plate, so the foreground meets the background
    // with no visible seam when a launcher masks the icon to a circle.
    //
    // foregroundImage is inset to ~52% of its canvas on purpose. Android reserves the outer third
    // for mask and parallax, guaranteeing only the inner 66% is visible, so artwork drawn to the
    // edge loses its extremities to whatever shape the launcher picks.
    adaptiveIcon: {
      backgroundColor: '#020E32',
      foregroundImage: './assets/android-icon-foreground.png',
      monochromeImage: './assets/android-icon-monochrome.png',
    },
    predictiveBackGestureEnabled: false,
  },
  web: {
    favicon: './assets/favicon.png',
  },
  plugins: [
    'expo-secure-store',
    'expo-sharing',
    '@react-native-community/datetimepicker',
    '@react-native-firebase/app',
    '@react-native-firebase/auth',
    './plugins/withRNFirebaseDisableSPM',
    [
      'expo-build-properties',
      {
        ios: {
          useFrameworks: 'static',
          // Required so RNFBApp/RNFBAuth link correctly under static frameworks — see
          // rnfirebase.io's Expo config-plugin install guide.
          forceStaticLinking: ['RNFBApp', 'RNFBAuth'],
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
