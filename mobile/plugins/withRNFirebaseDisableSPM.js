const { withPodfile } = require('expo/config-plugins');

// RN 0.75+ resolves Firebase via Swift Package Manager by default, and SPM only ships dynamic
// frameworks -- it conflicts with the static linkage app.config.ts requires for RNFBApp/RNFBAuth
// (expo-build-properties forceStaticLinking). `pod install` fails outright without this:
//
//   [!] [react-native-firebase] SPM + static linkage is not supported (target(s): Pods-Finora).
//
// Opting out of SPM keeps CocoaPods resolving Firebase instead, which supports static linkage.
// See @react-native-firebase/app/README.md's "iOS Dependency Resolution: SPM vs CocoaPods".
//
// PLAIN JAVASCRIPT, DELIBERATELY. This was a .ts file, which `expo prebuild` resolved happily and
// `eas init` did not:
//
//   Failed to resolve plugin for module "./plugins/withRNFirebaseDisableSPM"
//
// The Expo CLI registers a TypeScript loader before evaluating app.config.ts and its plugins; the
// EAS CLI resolves plugin modules through its own bundled copy of @expo/config, which does not.
// So a .ts plugin works locally and breaks every cloud build -- the failure appears at project
// linking, nowhere near the file that causes it. Published config plugins are plain JS for this
// reason. Types come from JSDoc instead, which `tsc` still checks.
const MARKER = '$RNFirebaseDisableSPM = true';

/** @type {import('expo/config-plugins').ConfigPlugin} */
const withRNFirebaseDisableSPM = (config) =>
  withPodfile(config, (podfileConfig) => {
    if (!podfileConfig.modResults.contents.includes(MARKER)) {
      podfileConfig.modResults.contents = podfileConfig.modResults.contents.replace(
        "require 'json'",
        `require 'json'\n\n${MARKER}\n`
      );
    }
    return podfileConfig;
  });

module.exports = withRNFirebaseDisableSPM;
