import { Platform } from 'react-native';

/**
 * What this app tells the backend about itself, for `ClientIdentity` to read.
 *
 * Support tickets and product feedback both record which client they came from. Nothing else in a
 * request carries that: the backend cannot tell an Android build from an iOS one, or either from
 * the web app, without being told.
 *
 * ## Why the version is a constant rather than read from the Expo config
 *
 * The obvious source is `Constants.expoConfig.version` from `expo-constants` — but that package is
 * not a dependency of this app and adding one to ship a single string is a poor trade, especially
 * on a native module whose version has to track the Expo SDK. A plain constant has no such cost.
 *
 * The risk a constant carries is drift: `app.config.ts` gets bumped for a release and this does
 * not, so every ticket reports a version the app has not been for months. `clientIdentity.test.ts`
 * closes that by reading the Expo config and asserting the two agree, which turns a silent lie into
 * a failing build at the moment someone bumps one and forgets the other.
 */
export const APP_VERSION = '1.0.0';

/**
 * `Platform.OS` is 'ios' | 'android' on device, and can be 'web' under Expo web or a test
 * environment. Anything that is not clearly one of the two native platforms falls back to WEB,
 * matching the backend's own default for an absent or unrecognised header — so an unexpected value
 * degrades to "we do not know" rather than inventing a platform.
 */
export function clientPlatform(): 'MOBILE_IOS' | 'MOBILE_ANDROID' | 'WEB' {
  if (Platform.OS === 'ios') return 'MOBILE_IOS';
  if (Platform.OS === 'android') return 'MOBILE_ANDROID';
  return 'WEB';
}

export const PLATFORM_HEADER = 'X-Client-Platform';
export const VERSION_HEADER = 'X-App-Version';
