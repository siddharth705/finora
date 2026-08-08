import { ConfigPlugin, withPodfile } from 'expo/config-plugins';

// RN 0.75+ resolves Firebase via Swift Package Manager by default, and SPM only ships dynamic
// frameworks -- it conflicts with the static linkage app.config.ts requires for RNFBApp/RNFBAuth
// (expo-build-properties forceStaticLinking). `pod install` fails outright without this:
//
//   [!] [react-native-firebase] SPM + static linkage is not supported (target(s): Pods-Finora).
//
// Opting out of SPM keeps CocoaPods resolving Firebase instead, which supports static linkage.
// See @react-native-firebase/app/README.md's "iOS Dependency Resolution: SPM vs CocoaPods".
const MARKER = '$RNFirebaseDisableSPM = true';

const withRNFirebaseDisableSPM: ConfigPlugin = (config) =>
  withPodfile(config, (config) => {
    if (!config.modResults.contents.includes(MARKER)) {
      config.modResults.contents = config.modResults.contents.replace(
        "require 'json'",
        `require 'json'\n\n${MARKER}\n`
      );
    }
    return config;
  });

export default withRNFirebaseDisableSPM;
