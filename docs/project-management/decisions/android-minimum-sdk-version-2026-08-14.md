# Mobile platform minimum version — decided 2026-08-14

**Status: Closed.** No engineering work required.

## Decision

| Platform | Minimum version | Status |
|---|---|---|
| Android | 7.0 (API 24) | Supported |
| Android 6.x (API 23) and below | — | Not supported |
| iOS | 16.4 | Supported |

## Verification

Checked directly against the repo, not assumed:

- `mobile/android/` and `mobile/ios/` are Expo-prebuild output and gitignored
  (`mobile/.gitignore:46-47`) — there is no committed `build.gradle` or `Podfile` to hand-edit;
  whatever ships is whatever Expo SDK 57 generates.
- A built Android manifest (`expo-constants` merged manifest under
  `mobile/node_modules/expo/node_modules/expo-constants/android/build/.../AndroidManifest.xml`)
  shows `android:minSdkVersion="24"`.
- [mobile/ios/Podfile:26](../../../mobile/ios/Podfile) sets `platform :ios, '16.4'` as the fallback
  when no `ios.deploymentTarget` override is present in `podfile_properties` — and none is.
- [mobile/app.config.ts](../../../mobile/app.config.ts)'s `expo-build-properties` plugin only
  overrides iOS static-linking settings for Firebase; it sets no `android.minSdkVersion` or
  `ios.deploymentTarget`.
- Stack: Expo SDK 57, React Native 0.86.2, React 19.2 ([mobile/package.json](../../../mobile/package.json)).

Both floors are Expo SDK 57's own defaults, inherited rather than chosen. Nothing in the project
overrides them.

## Reasoning

- Fintech app — security/reliability posture favors a modern OS floor over legacy device reach.
- Supporting Android 6 would mean pinning to an older Expo SDK, which conflicts with
  `mobile/AGENTS.md`'s instruction to build against the current (v57) API surface, and would put
  Firebase/RN 0.86 New Architecture compatibility at risk for a ~10-year-old OS with negligible
  expected user share.
- No product or engineering change is needed to act on this — it's already what ships today.

## Revisit condition

Only reconsider if production analytics show a meaningful share of active/prospective users on
Android 6 or iOS <16.4. Until then, treat both floors as the platform support policy.
