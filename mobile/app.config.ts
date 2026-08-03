import { existsSync } from 'fs';
import path from 'path';
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
// Omitting it instead degrades the same way the backend's FirebaseConfig does: the app still
// builds and runs, and only phone-verification-gated screens fail until credentials are in place.
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
    adaptiveIcon: {
      backgroundColor: '#E6F4FE',
      foregroundImage: './assets/android-icon-foreground.png',
      backgroundImage: './assets/android-icon-background.png',
      monochromeImage: './assets/android-icon-monochrome.png',
    },
    predictiveBackGestureEnabled: false,
  },
  web: {
    favicon: './assets/favicon.png',
  },
  plugins: [
    'expo-secure-store',
    '@react-native-firebase/app',
    '@react-native-firebase/auth',
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

export default config;
