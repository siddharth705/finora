import { PermissionsAndroid, Platform } from 'react-native';
import { getMessaging, getToken as fbGetToken, onTokenRefresh as fbOnTokenRefresh, requestPermission as fbRequestPermission } from '@react-native-firebase/messaging';
import { deviceTokensApi, type DevicePlatform } from '../api/endpoints';

/**
 * Task 14 -- the mobile half of push. Without this module, Task 9's POST /device-tokens endpoint
 * is never called: no `device_tokens` row is ever written, and every push silently no-ops while
 * the backend test suite (and every other layer) stays green. See AuthContext.tsx for where these
 * two functions are actually wired into the session lifecycle.
 *
 * Both exported functions take their collaborators (`messaging`, `postDeviceToken`/
 * `deleteDeviceToken`) as optional dependency-injected arguments, defaulting to the real
 * @react-native-firebase/messaging module and the real backend endpoints. Tests supply fakes;
 * production callers call these with no arguments at all.
 */

/** A minimal, method-shaped view over the modular @react-native-firebase/messaging API -- built
 *  once by defaultMessaging() below, or substituted wholesale by a test. */
export interface PushMessaging {
  requestPermission(): Promise<number>;
  getToken(): Promise<string>;
  onTokenRefresh(listener: (token: string) => void): () => void;
}

export type PostDeviceTokenFn = (body: { token: string; platform: DevicePlatform }) => Promise<unknown>;
export type DeleteDeviceTokenFn = (body: { token: string }) => Promise<unknown>;

let cachedMessaging: PushMessaging | null = null;

/** Lazily wraps the real modular API into the method-shaped PushMessaging interface above.
 *  Lazy (not built at module scope) so importing this file never touches the native module --
 *  only calling registerDeviceToken()/revokeDeviceToken() with no override does. */
function defaultMessaging(): PushMessaging {
  if (!cachedMessaging) {
    const instance = getMessaging();
    cachedMessaging = {
      requestPermission: () => fbRequestPermission(instance),
      getToken: () => fbGetToken(instance),
      onTokenRefresh: (listener) => fbOnTokenRefresh(instance, listener),
    };
  }
  return cachedMessaging;
}

function defaultPostDeviceToken(body: { token: string; platform: DevicePlatform }) {
  return deviceTokensApi.register(body);
}

function defaultDeleteDeviceToken(body: { token: string }) {
  return deviceTokensApi.revoke(body);
}

function currentPlatform(): DevicePlatform {
  return Platform.OS === 'android' ? 'ANDROID' : 'IOS';
}

/**
 * Mirrors @react-native-firebase/messaging's own AuthorizationStatus enum (NOT_DETERMINED: -1,
 * DENIED: 0, AUTHORIZED: 1, PROVISIONAL: 2, EPHEMERAL: 3) as plain numbers rather than importing
 * it -- this file's only import from the package is the handful of functions defaultMessaging()
 * wraps, so a test's mock of that module never has to reproduce this enum too.
 */
const GRANTED_AUTHORIZATION_STATUSES = new Set([1, 2]);

/**
 * iOS always needs an explicit prompt (messaging.requestPermission()). Android needs the
 * POST_NOTIFICATIONS *runtime* permission only on API 33+ -- below that it's implicit -- and
 * critically, RNFB's own requestPermission() does NOT surface that grant on Android: the native
 * module's Android implementation (NativeRNFBTurboMessaging#requestPermission) unconditionally
 * resolves AUTHORIZED regardless of the real OS permission state. So Android's real signal has to
 * come from PermissionsAndroid directly, checked first and short-circuiting on denial.
 */
async function ensureNotificationPermission(messaging: PushMessaging): Promise<boolean> {
  if (Platform.OS === 'android' && typeof Platform.Version === 'number' && Platform.Version >= 33) {
    const result = await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS);
    if (result !== PermissionsAndroid.RESULTS.GRANTED) return false;
  }
  const status = await messaging.requestPermission();
  return GRANTED_AUTHORIZATION_STATUSES.has(status);
}

/**
 * Logs enough to debug a registration failure without ever writing a raw device token to the
 * device's logs. Deliberately narrow: an Axios error's `.config.data` is the exact JSON body just
 * sent, which for this call IS the token -- logging the error object whole (or anything under
 * `.config`) would leak it. Only a message and an HTTP status code (when present) are safe.
 */
function logPushFailure(context: string, error: unknown): void {
  const status = (error as { response?: { status?: number } } | null)?.response?.status;
  const message = error instanceof Error ? error.message : 'unknown error';
  console.warn(`[pushRegistration] ${context}`, { message, status });
}

async function postToken(postDeviceToken: PostDeviceTokenFn, token: string): Promise<void> {
  try {
    await postDeviceToken({ token, platform: currentPlatform() });
  } catch (error) {
    // A failed registration must never block the user or surface an error -- push is an
    // enhancement, not a requirement of using the app. Log and swallow.
    logPushFailure('failed to register device token', error);
  }
}

/** Unsubscribes whatever onTokenRefresh listener a previous registerDeviceToken() call attached,
 *  so repeated calls (login, then a later foreground) don't stack up duplicate listeners each
 *  re-posting the same rotated token. */
let unsubscribeTokenRefresh: (() => void) | null = null;

export interface RegisterDeviceTokenDeps {
  postDeviceToken?: PostDeviceTokenFn;
  messaging?: PushMessaging;
}

/**
 * Requests notification permission, and on grant, registers this device's current FCM token with
 * the backend and stays subscribed to future rotations (Firebase rotates the token on reinstall,
 * restore, and app-data clear -- without re-posting then, the user silently stops receiving push).
 *
 * Never throws. A denied prompt is a normal outcome, not an error, and is handled identically to
 * any other reason no token gets registered: the function simply returns.
 *
 * Callers (AuthContext) MUST NOT call this before phone verification has completed --
 * PhoneVerificationFilter on the backend 403s POST /device-tokens for a verified-pending session
 * the same as it would any other unexempted endpoint, and a failed registration here would just be
 * silently swallowed, leaving no token ever stored for that user.
 */
export async function registerDeviceToken(deps: RegisterDeviceTokenDeps = {}): Promise<void> {
  // Resolved INSIDE the try block, deliberately: defaultMessaging() calls the real, synchronous
  // getMessaging(), which can throw (e.g. no native Firebase app registered yet). An async
  // function auto-wraps a synchronous throw into a rejected promise rather than throwing back to
  // the caller -- but a caller that fires this with `void registerDeviceToken()` (every caller in
  // this app does) never attaches a .catch(), so an unresolved dependency built outside this try
  // block would surface as an unhandled promise rejection instead of the quiet log-and-swallow
  // this function promises everywhere else.
  try {
    const messaging = deps.messaging ?? defaultMessaging();
    const postDeviceToken = deps.postDeviceToken ?? defaultPostDeviceToken;

    const granted = await ensureNotificationPermission(messaging);
    if (!granted) return;

    const token = await messaging.getToken();
    if (!token) return;

    await postToken(postDeviceToken, token);

    unsubscribeTokenRefresh?.();
    unsubscribeTokenRefresh = messaging.onTokenRefresh((nextToken) => {
      if (!nextToken) return;
      void postToken(postDeviceToken, nextToken);
    });
  } catch (error) {
    logPushFailure('registerDeviceToken failed', error);
  }
}

export interface RevokeDeviceTokenDeps {
  deleteDeviceToken?: DeleteDeviceTokenFn;
  messaging?: PushMessaging;
}

/**
 * Revokes this device's current token server-side. Must be called (and awaited) BEFORE the auth
 * token is cleared from storage -- see AuthContext.tsx's logout(), which calls this while the
 * bearer token is still present so the request carries an Authorization header instead of 401ing.
 *
 * Never throws -- logging out must succeed locally regardless of whether the revoke call reaches
 * the backend.
 */
export async function revokeDeviceToken(deps: RevokeDeviceTokenDeps = {}): Promise<void> {
  // Read-then-clear-then-call, in that order, and in its OWN try/catch separate from the actual
  // revoke call below: `unsubscribe` ultimately calls RNFB's native event-removal, which can throw
  // synchronously, and every production caller fires this whole function with
  // `void revokeDeviceToken()` (AuthContext.tsx's logout() awaits it, but with no .catch() of its
  // own) -- so an unguarded throw here would surface as an unhandled rejection during logout
  // despite this function's own "never throws" contract. Clearing the stored reference BEFORE
  // calling it means a throw from a stale/already-broken closure can never leave it behind for a
  // later registerDeviceToken()/revokeDeviceToken() call to stack another subscription on top of,
  // or retry the same failing unsubscribe again. A SEPARATE try/catch from the revoke call below
  // (not one shared try wrapping both) matters just as much: a broken native listener removal is
  // unrelated to whether the backend can still be told to revoke the token, so it must not abort
  // that call.
  const unsubscribe = unsubscribeTokenRefresh;
  unsubscribeTokenRefresh = null;
  try {
    unsubscribe?.();
  } catch (error) {
    logPushFailure('failed to unsubscribe from token refresh', error);
  }

  // See registerDeviceToken()'s own comment on why dependency resolution happens inside the try.
  try {
    const messaging = deps.messaging ?? defaultMessaging();
    const deleteDeviceToken = deps.deleteDeviceToken ?? defaultDeleteDeviceToken;

    const token = await messaging.getToken();
    if (!token) return;
    await deleteDeviceToken({ token });
  } catch (error) {
    logPushFailure('revokeDeviceToken failed', error);
  }
}
