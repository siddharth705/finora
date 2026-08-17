import axios from 'axios';
import { safeStorage } from '../lib/safeStorage';

// Same fixup as the web app's client.ts: whatever EXPO_PUBLIC_API_BASE_URL is set to always
// resolves to exactly one /api/v1 suffix, whether or not the value already includes it.
export function normalizeApiBase(rawBase: string): string {
  const trimmed = rawBase.replace(/\/+$/, '');
  return trimmed.endsWith('/api/v1') ? trimmed : `${trimmed}/api/v1`;
}

// Expo inlines any env var prefixed EXPO_PUBLIC_ into the JS bundle at build time (mirrors Vite's
// VITE_ convention) -- see mobile/.env.example. Unlike the web app, there's no same-origin
// relative-path fallback: a native app has no dev-server proxy to fall back on, so this must
// always resolve to an absolute origin.
const rawBase = process.env.EXPO_PUBLIC_API_BASE_URL;
if (!rawBase) {
  throw new Error(
    'EXPO_PUBLIC_API_BASE_URL is not set. Copy mobile/.env.example to mobile/.env.local and set it to your backend origin.'
  );
}
const BASE_URL = normalizeApiBase(rawBase);

export const api = axios.create({ baseURL: BASE_URL });

// Interceptor-free instance for the /auth/refresh call itself, same reasoning as the web app: if
// the refresh call went through `api`'s own response interceptor and also got a 401, it would
// recursively trigger another refresh attempt.
export const rawApi = axios.create({ baseURL: BASE_URL });

export interface ApiEnvelope<T> {
  success: boolean;
  message: string;
  data: T;
  timestamp: string;
  errorCode: string | null;
  requestId: string | null;
}

const AUTH_ENDPOINTS_NO_TOKEN = ['/auth/login', '/auth/register', '/auth/refresh', '/auth/forgot-password', '/auth/reset-password', '/auth/reactivate'];

const TOKEN_KEY = 'finora_token';
const REFRESH_TOKEN_KEY = 'finora_refresh_token';

// Request interceptor is async here (the web version's is sync) because SecureStore's stable API
// is Promise-based, unlike localStorage -- axios awaits whatever a request interceptor returns,
// so this needs no other change.
api.interceptors.request.use(async (config) => {
  const isAuthEndpoint = AUTH_ENDPOINTS_NO_TOKEN.some((path) => config.url?.includes(path));
  if (!isAuthEndpoint) {
    const token = await safeStorage.getItem(TOKEN_KEY);
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
  }
  return config;
});

// The web version's clearSessionAndRedirect() called `window.location.href = '/login'` directly
// -- there's no window/location on native, and this module has no business importing a navigation
// library. Instead it exposes two callbacks the app registers once at startup (see AuthContext),
// keeping the API client itself free of any navigation dependency.
type SessionCallback = () => void;
let onSessionExpired: SessionCallback = () => {};
let onPhoneVerificationRequired: SessionCallback = () => {};

export function setSessionCallbacks(handlers: {
  onSessionExpired?: SessionCallback;
  onPhoneVerificationRequired?: SessionCallback;
}) {
  if (handlers.onSessionExpired) onSessionExpired = handlers.onSessionExpired;
  if (handlers.onPhoneVerificationRequired) onPhoneVerificationRequired = handlers.onPhoneVerificationRequired;
}

// Mirrors every key AuthContext.logout() clears on the web app.
async function clearSessionAndRedirect() {
  await Promise.all([
    safeStorage.removeItem(TOKEN_KEY),
    safeStorage.removeItem(REFRESH_TOKEN_KEY),
    safeStorage.removeItem('finora_email'),
    safeStorage.removeItem('finora_name'),
    safeStorage.removeItem('finora_phone_verified'),
  ]);
  onSessionExpired();
}

// Every backend response arrives wrapped in a standard envelope:
// { success, message, data, timestamp, errorCode, requestId }. This interceptor transparently
// unwraps it, same as the web app.
function unwrapEnvelope(response: any) {
  if (response.data && typeof response.data === 'object' && 'success' in response.data) {
    response.data = response.data.data;
  }
  return response;
}

// Refresh tokens rotate server-side on every use (RefreshTokenService.rotate()) -- presenting an
// already-rotated token is treated as a theft signal and revokes every active session for the
// user. This shared in-flight promise (same pattern as the web app) means N requests that 401
// around the same moment all await the SAME refresh call instead of each independently racing to
// present the same soon-to-be-stale refresh token.
let refreshInFlight: Promise<{ token: string; refreshToken: string }> | null = null;

/**
 * Reads the stored token, rotates it, and persists the new pair -- all inside the shared promise.
 *
 * The persistence belongs in here, not in the caller, and that is the whole point. When the write
 * lived in the interceptor, `.finally` cleared this guard the instant the network call settled,
 * while storage still held the OLD token for the length of two further awaits. A 401 arriving in
 * that window found the guard open, read the retired token, and presented it again --
 * RefreshTokenService.rotate() reads a second presentation as theft and revokes every session the
 * user has, on every device. So one badly-timed request signs you out everywhere.
 *
 * Reproduced deterministically in refreshRace.test.ts before this was changed: the fake server
 * recorded ["R1", "R1"]. Holding the guard until the new pair is written means a late caller either
 * joins this promise or reads storage that is already current -- there is no ordering left in which
 * a retired token can be sent.
 *
 * Reading the token in here rather than accepting it as an argument closes the same window from the
 * other side: a caller that read storage before joining would otherwise hand in a value that was
 * already stale by the time it was used.
 */
function refreshAccessToken(): Promise<{ token: string; refreshToken: string }> {
  if (!refreshInFlight) {
    refreshInFlight = (async () => {
      const stored = await safeStorage.getItem(REFRESH_TOKEN_KEY);
      if (!stored) throw new Error('No refresh token stored');
      const { authApi } = await import('./endpoints');
      const refreshed = await authApi.refresh(stored);
      await safeStorage.setItem(TOKEN_KEY, refreshed.token);
      await safeStorage.setItem(REFRESH_TOKEN_KEY, refreshed.refreshToken);
      return refreshed;
    })().finally(() => {
      refreshInFlight = null;
    });
  }
  return refreshInFlight;
}

api.interceptors.response.use(
  (response) => unwrapEnvelope(response),
  async (error) => {
    const originalRequest = error.config;

    // A 401 on anything other than an auth endpoint: try exactly once to refresh the access token
    // and replay the original request.
    //
    // Bug fix: this used to exclude only '/auth/refresh', not the whole AUTH_ENDPOINTS_NO_TOKEN
    // list the request interceptor above already uses -- the web app's client.ts carries this
    // exact fix and its reasoning, and the port to mobile didn't bring it across. A 401 from
    // /auth/login means "wrong password", not "your session expired", but this branch treated the
    // two identically. For a signed-out user with a stale refresh token still in SecureStore (an
    // app killed mid-logout, or a logout whose network call failed), one mistyped password sent
    // that stale token to /auth/refresh -- and presenting an already-rotated refresh token is
    // exactly what RefreshTokenService.rotate() treats as a theft signal, revoking every active
    // session for that user on every device. A typo on the sign-in screen could sign you out
    // everywhere.
    const isAuthEndpoint = AUTH_ENDPOINTS_NO_TOKEN.some((path) => originalRequest.url?.includes(path));

    if (error.response?.status === 401 && !originalRequest._retried && !isAuthEndpoint) {
      originalRequest._retried = true;

      // Reading the stored token, rotating it and persisting the result all happen inside
      // refreshAccessToken so they cannot be interleaved -- see its comment for the session-wide
      // sign-out that splitting them caused. A missing token throws there too, so "no session" and
      // "refresh rejected" land on the same branch, which is what they both mean here.
      try {
        const refreshed = await refreshAccessToken();
        originalRequest.headers.Authorization = `Bearer ${refreshed.token}`;
        return api(originalRequest);
      } catch {
        await clearSessionAndRedirect();
        return Promise.reject(error);
      }
    }

    // Backend is the source of truth on phone verification (PhoneVerificationFilter) -- a valid
    // session that hasn't completed verification yet. Send the app to finish it via the
    // registered callback rather than leaving every subsequent call silently failing.
    if (error.response?.status === 403 && error.response?.data?.errorCode === 'PHONE_VERIFICATION_REQUIRED') {
      onPhoneVerificationRequired();
      return Promise.reject(error);
    }

    // Error responses use the same envelope ({success:false, message, errorCode, details}) --
    // surface the message where callers already expect err.response.data.message. `details` is
    // carried through too (not just message/errorCode): AUTH_ACCOUNT_DEACTIVATED's reactivation
    // token travels there (see ApiException/ApiResponse on the backend), same as the web app's
    // client.ts -- dropping it silently would make the reactivation flow unreachable here too.
    if (error.response?.data?.message) {
      error.response.data = {
        message: error.response.data.message,
        errorCode: error.response.data.errorCode,
        details: error.response.data.details,
      };
    }
    return Promise.reject(error);
  }
);
