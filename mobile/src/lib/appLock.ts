import * as LocalAuthentication from 'expo-local-authentication';
import * as SecureStore from 'expo-secure-store';
import { safeStorage } from './safeStorage';

/**
 * SEC-09 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). Optional biometric
 * app-lock -- opt-in, same "no lockout risk for anyone who hasn't chosen it" reasoning the
 * backend's SEC-03 admin MFA already applies: nobody is worse off than today until they
 * deliberately turn this on in Settings > Security, and turning it off is always self-service
 * (no support flow needed, unlike a locked-out account).
 *
 * The enabled flag lives in SecureStore, not the account's server-side settings -- this is a
 * per-device preference (the same person may reasonably want it on their phone and not bother on
 * a tablet), not a fact about the account itself, and it has to be readable before the user has
 * proven anything (RootNavigator/AppLockGate need it to decide whether to show the lock screen at
 * all), which rules out anything that would itself require being unlocked first.
 */
const ENABLED_KEY = 'finora_app_lock_enabled';

/**
 * Module-level, not component state -- authenticate() has two independent callers
 * (AppLockGate's own re-lock prompt, and AppLockSection's "confirm to enable" prompt in
 * Settings), and presenting the native Face ID/Touch ID sheet drives AppState through
 * inactive/background and back to active as a side effect of the prompt itself, confirmed
 * on-device. AppLockGate's foreground listener needs to recognize BOTH sources -- state scoped
 * to AppLockGate alone left it blind to AppLockSection's call entirely, which is what turning the
 * setting ON in Settings actually triggers first: that prompt's own blip reached AppLockGate,
 * which had no idea an authenticate() call was running anywhere, decided this looked like a
 * genuine foreground return, and locked the whole app on top of the confirmation the user had
 * just given -- the reported loop's real starting point, not something scoped to AppLockGate's
 * own re-lock flow.
 */
let authenticatingCount = 0;
let lastResolvedAt = 0;

/** True for the entire duration of any authenticate() call in progress, from any caller. */
export function isAuthenticating(): boolean {
  return authenticatingCount > 0;
}

/** True if some authenticate() call (from any caller) resolved within the last `windowMs`. */
export function justFinishedAuthenticating(windowMs: number): boolean {
  return Date.now() - lastResolvedAt < windowMs;
}

/**
 * D5 (Track D security cleanup, docs/project-management/plans/mobile-correctness-trust-roadmap.md).
 * Opening the OS share sheet (Sharing.shareAsync -- statementImportsApi.downloadFile,
 * supportApi.downloadAttachment, reportExport.ts's shareCsv/sharePdf) backgrounds this app exactly
 * the same way a biometric prompt does: AppState swings inactive/background then back to active
 * as a side effect of native UI taking over the screen, not because the user actually left and
 * came back. Before this existed, AppLockGate's foreground listener had no way to tell that blip
 * apart from a genuine return, and re-locked the app in the middle of (or immediately after) the
 * user's own share flow. Same isXInFlight/justFinishedX shape as isAuthenticating/
 * justFinishedAuthenticating above, for the identical reason: two independent native notification
 * paths that don't synchronize with each other, so an in-flight check alone isn't enough on its
 * own -- see AppLockGate's own comment on the Face ID sheet for the on-device-confirmed version
 * of this same race.
 */
let sharingCount = 0;
let lastShareFinishedAt = 0;

/** True for the entire duration of any withShareSuppression()-wrapped share in progress. */
export function isSharing(): boolean {
  return sharingCount > 0;
}

/** True if some withShareSuppression()-wrapped share resolved within the last `windowMs`. */
export function justFinishedSharing(windowMs: number): boolean {
  return Date.now() - lastShareFinishedAt < windowMs;
}

/** Wraps a Sharing.shareAsync call (or a whole write-then-share flow) so AppLockGate's foreground
 *  listener can recognize the AppState blip it causes -- see this section's own doc comment. */
export async function withShareSuppression<T>(fn: () => Promise<T>): Promise<T> {
  sharingCount++;
  try {
    return await fn();
  } finally {
    sharingCount--;
    lastShareFinishedAt = Date.now();
  }
}

/** Test-only, same reasoning as __resetAuthenticatingStateForTests below. */
export function __resetSharingStateForTests(): void {
  sharingCount = 0;
  lastShareFinishedAt = 0;
}

/** Test-only. This module-level state is meant to persist for the life of the app process, but
 *  that is exactly what makes it a cross-test hazard: `lastResolvedAt` is stamped with the REAL
 *  wall-clock time by every test that completes a real authenticate() call, and Jest runs tests
 *  in the same file milliseconds apart -- comfortably inside justFinishedAuthenticating's own
 *  windows -- so without a reset between tests, an early test's authenticate() call can silently
 *  suppress a later, unrelated test's foreground check. */
export function __resetAuthenticatingStateForTests(): void {
  authenticatingCount = 0;
  lastResolvedAt = 0;
}

export async function isSupported(): Promise<boolean> {
  const [hasHardware, isEnrolled] = await Promise.all([
    LocalAuthentication.hasHardwareAsync(),
    LocalAuthentication.isEnrolledAsync(),
  ]);
  return hasHardware && isEnrolled;
}

/**
 * D1 (docs/project-management/plans/mobile-correctness-trust-roadmap.md, Track D). Deliberately
 * NOT routed through {@link safeStorage}: that module's contract collapses a genuinely-absent key
 * and a THROWN read into the identical `null` -- fine for most call sites, wrong here, because the
 * two facts mean opposite things for this specific flag. "Absent" means the user never turned the
 * lock on (fine to open). "Threw" means SecureStore itself is unreadable (a keychain/keystore
 * fault) and whether the lock is on cannot actually be determined -- collapsing that to "open"
 * would silently skip the lock screen the one time it's most likely something is genuinely wrong.
 * `authenticate()` above already fails closed on its own errors; this makes the enabled-check do
 * the same, rather than reusing a contract designed for lower-stakes reads. `true` here is safe,
 * not a lockout risk: AppLockGate's lock screen always keeps its own "Sign Out" escape hatch.
 */
export async function isEnabled(): Promise<boolean> {
  try {
    return (await SecureStore.getItemAsync(ENABLED_KEY)) === 'true';
  } catch {
    return true;
  }
}

export async function setEnabled(enabled: boolean): Promise<void> {
  if (enabled) {
    await safeStorage.setItem(ENABLED_KEY, 'true');
  } else {
    // Removed rather than written as 'false': isEnabled()'s own contract ("=== 'true'") already
    // treats anything else as off, and there's no reason to persist a value at all for the
    // (default, most common) disabled state.
    await safeStorage.removeItem(ENABLED_KEY);
  }
}

/** @returns true only on a genuine successful authentication. Every failure mode (wrong
 *  biometric, user cancelled, hardware unavailable, nothing enrolled) is collapsed to false --
 *  the caller has exactly one thing to decide either way: stay locked, or unlock. */
export async function authenticate(promptMessage: string): Promise<boolean> {
  authenticatingCount++;
  try {
    const result = await LocalAuthentication.authenticateAsync({
      promptMessage,
      // Device passcode fallback left ON (the default) deliberately, not disabled: biometric
      // hardware can legitimately fail (a wet finger, a face partly covered, a temporary sensor
      // fault), and disableDeviceFallback would turn that into a dead end with no way back into
      // an app the user cannot currently reach Settings inside of to turn the lock back off.
      // Requiring the phone's own passcode is still a real, meaningful check -- an attacker who
      // has neither the biometric nor the passcode still cannot get in.
      cancelLabel: 'Cancel',
    });
    return result.success;
  } catch {
    // authenticateAsync() itself throwing (rather than resolving {success: false}) is not
    // documented as a normal outcome, but this function's whole contract is "never throws, only
    // ever answers locked or unlocked" -- a thrown error must fail closed to locked, the same as
    // every other rejection path.
    return false;
  } finally {
    authenticatingCount--;
    lastResolvedAt = Date.now();
  }
}
