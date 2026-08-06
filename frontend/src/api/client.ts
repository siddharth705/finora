import axios from 'axios';
import { safeStorage } from '../lib/safeStorage';

// Bug fix (production-readiness pass): this was a hardcoded relative path with no env-driven
// override at all. That's fine in local dev, where Vite's own dev-server proxy (vite.config.ts's
// server.proxy, matching '/api' -> http://localhost:8080) forwards it to the backend — but
// vite.config.ts's `server.proxy` ONLY applies to `vite dev`; it has zero effect on the actual
// production build (`vite build` just produces static files, with nothing left to do any
// proxying). Deployed as-is to a static host on its own origin (Cloudflare, in Finora's current
// deployment), '/api/v1/...' resolves against THAT origin, not the separate Railway backend --
// there is no route there for it to hit. This is almost certainly why the deployed frontend
// can't reach the backend at all right now, and it's also exactly why CORS_ORIGINS was already
// configured on the backend for cross-origin access in the first place (see CorsConfig) -- that
// setup only makes sense if the frontend is meant to call the backend's own absolute origin
// directly, cross-origin, not through a same-origin relative path.
//
// Second bug fix, caught from an actual production CORS error: VITE_API_BASE_URL got set to the
// bare Railway origin (e.g. https://confident-wonder-dev.up.railway.app) without the /api/v1
// backend routes actually live under -- every request silently lost that path segment, so
// register/login (and everything else) hit "<origin>/auth/register" instead of
// "<origin>/api/v1/auth/register", a route that doesn't exist. normalizeApiBase() below makes
// this correct either way: whether the env var is set to the bare origin or already includes
// /api/v1, the result always has exactly one /api/v1 suffix, so this specific misconfiguration
// can't silently break every API call again.
export function normalizeApiBase(rawBase: string): string {
  const trimmed = rawBase.replace(/\/+$/, ''); // strip trailing slash(es), if any
  return trimmed.endsWith('/api/v1') ? trimmed : `${trimmed}/api/v1`;
}

const BASE_URL = import.meta.env.VITE_API_BASE_URL
  ? normalizeApiBase(import.meta.env.VITE_API_BASE_URL)
  : '/api/v1';

// withCredentials is what makes RefreshTokenCookie work at all. The backend issues the refresh
// token as an HttpOnly, Secure, SameSite=Lax cookie scoped to /api/v1/auth, and CorsConfig sets
// allowCredentials(true) specifically so a browser may send it cross-origin -- but axios defaults
// withCredentials to false, so on the deployment this app is built for (static frontend on
// Cloudflare, backend on Railway) the browser stored neither the Set-Cookie nor sent it back.
// Every refresh fell through to RefreshTokenCookie.resolve()'s body-token branch and the whole
// cookie mechanism was inert.
//
// This restores the cookie transport; it does NOT on its own deliver the XSS mitigation the
// cookie exists for. AuthContext.persist still writes the same refresh token to localStorage,
// where script can read it, and the backend still accepts a body token. Removing the
// localStorage copy is the other half and is a change to how the session is held, not a
// one-line fix -- see docs. Restoring the transport first is what makes that half possible.
export const api = axios.create({ baseURL: BASE_URL, withCredentials: true });

// A separate, interceptor-free instance specifically for the /auth/refresh call itself —
// if the refresh call went through `api`'s own response interceptor and also got a 401
// (expired/invalid refresh token), it would recursively trigger another refresh attempt.
// Keeping it on a bare instance avoids that entirely.
export const rawApi = axios.create({ baseURL: BASE_URL, withCredentials: true });

// Bug fix: `api` calls get response.data pre-unwrapped by unwrapEnvelope() below, so their
// typed generics are always the INNER payload shape (e.g. api.post<{message: string}>(...)) --
// but rawApi has no interceptors at all, so a rawApi call's response.data is still the raw
// { success, message, data, ... } envelope the backend's ApiResponse<T> record always sends.
// authApi.refresh (the only current rawApi caller) already correctly reads response.data.data
// at runtime, but was typing its generic as the inner shape instead of ApiEnvelope<inner shape>
// -- harmless at runtime (TypeScript types don't exist after compilation), but a real compile
// error the moment anything actually ran `tsc` on it. Exported so any future rawApi caller can
// type itself correctly instead of guessing.
export interface ApiEnvelope<T> {
  success: boolean;
  message: string;
  data: T;
  timestamp: string;
  errorCode: string | null;
  requestId: string | null;
}

// Auth endpoints never need (and shouldn't receive) a Bearer token — sending a stale one
// serves no purpose here since these are all permitAll server-side, and not sending it at all
// is simply cleaner than relying on the backend to ignore a token that isn't relevant.
const AUTH_ENDPOINTS_NO_TOKEN = ['/auth/login', '/auth/register', '/auth/refresh', '/auth/forgot-password', '/auth/reset-password'];

api.interceptors.request.use((config) => {
  const isAuthEndpoint = AUTH_ENDPOINTS_NO_TOKEN.some((path) => config.url?.includes(path));
  if (!isAuthEndpoint) {
    const token = safeStorage.getItem('finora_token');
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
  }
  return config;
});

/**
 * Where a forced-sign-out reason waits for the login page to pick it up.
 *
 * clearSessionAndRedirect() ends with `window.location.href`, a FULL page navigation — React
 * unmounts, so router state (which Login.tsx already uses for the "password updated" banner) can't
 * carry anything across it. Without this handoff the backend's explanation of why the session ended
 * was simply discarded: the user got bounced to a login screen with no indication that anything had
 * happened, let alone that their session expired or that every device was signed out as a
 * precaution after a suspected stolen token. Every message this app has for that moment existed
 * server-side and reached nobody.
 *
 * Read once and deleted by Login.tsx, so a reason can't resurface on an unrelated later visit.
 */
export const SESSION_ENDED_REASON_KEY = 'finora_session_ended_reason';

function clearSessionAndRedirect(reason?: string) {
  // Mirrors every key AuthContext.logout() clears -- this used to miss finora_phone_verified,
  // leaving that one flag behind in localStorage after a forced session expiry. Currently
  // inert in practice (ProtectedRoute redirects on a missing token before it would ever read
  // this flag), but it's a real hygiene gap: a stale, unrelated-to-the-next-session value left
  // sitting in storage is exactly the kind of thing that turns into a real bug the moment some
  // future feature reads phoneVerified independently of token presence.
  safeStorage.removeItem('finora_token');
  safeStorage.removeItem('finora_refresh_token');
  safeStorage.removeItem('finora_email');
  safeStorage.removeItem('finora_name');
  safeStorage.removeItem('finora_phone_verified');
  // Written AFTER the clears above, or it would be wiped by them the moment a future key is added
  // to that list. Falls back to generic copy when the backend gave no reason (no refresh token was
  // ever stored, so nothing was asked of the server) -- "something ended your session" is still
  // more useful than a silent bounce to the login screen.
  safeStorage.setItem(SESSION_ENDED_REASON_KEY,
    reason || 'Your session has ended. Please sign in again to continue.');
  window.location.href = '/login';
}

// Every backend response arrives wrapped in a standard envelope:
// { success, message, data, timestamp, errorCode, requestId }. This interceptor transparently
// unwraps it so endpoints.ts keeps reading response.data exactly as before.
function unwrapEnvelope(response: any) {
  if (response.data && typeof response.data === 'object' && 'success' in response.data) {
    response.data = response.data.data;
  }
  return response;
}

// Bug fix: refresh tokens rotate server-side on every use (RefreshTokenService.rotate()) --
// presenting an already-rotated token isn't just rejected, it's treated as a THEFT signal and
// revokes every active session for the user, everywhere (see that method's own doc comment: "a
// strong signal it was stolen... revoke every active token for the user"). Without this shared
// promise, N requests that happen to 401 around the same moment (a very real scenario: the access
// token expires while a tab is idle, then several components refetch at once when it regains
// focus) each independently called authApi.refresh() with the SAME refresh token — only the
// first ever succeeds; the other N-1 present an already-rotated token and trip the backend's
// theft response, force-logging the user out of every device over a client-side race, not actual
// theft. Now every 401 arriving while a refresh is already in flight awaits that SAME promise
// instead of starting its own.
let refreshInFlight: Promise<{ token: string; refreshToken: string }> | null = null;

function refreshAccessToken(refreshToken: string): Promise<{ token: string; refreshToken: string }> {
  if (!refreshInFlight) {
    refreshInFlight = (async () => {
      const { authApi } = await import('./endpoints');
      return authApi.refresh(refreshToken);
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
    // and replay the original request. If that also fails, the session is truly gone — clear it
    // and bounce to login rather than looping.
    //
    // Excluding EVERY auth endpoint, not just /auth/refresh, is the point. A 401 from /auth/login
    // means "wrong password", not "your session expired" — but this branch treated the two
    // identically, so a failed sign-in ran clearSessionAndRedirect() and hard-navigated to /login,
    // destroying the React state holding Login.tsx's own "Login failed. Check your credentials."
    // message before it could render. The user saw a page flash and no explanation of what went
    // wrong. admin-portal/src/api/client.ts already guards this exact case (its own comment
    // describes the same symptom); this app was never updated to match, though it already had the
    // AUTH_ENDPOINTS_NO_TOKEN list above and used it in the request interceptor.
    const isAuthEndpoint = AUTH_ENDPOINTS_NO_TOKEN.some((path) => originalRequest.url?.includes(path));

    if (error.response?.status === 401 && !originalRequest._retried && !isAuthEndpoint) {
      originalRequest._retried = true;
      const refreshToken = safeStorage.getItem('finora_refresh_token');

      if (refreshToken) {
        try {
          const refreshed = await refreshAccessToken(refreshToken);
          safeStorage.setItem('finora_token', refreshed.token);
          safeStorage.setItem('finora_refresh_token', refreshed.refreshToken);
          originalRequest.headers.Authorization = `Bearer ${refreshed.token}`;
          return api(originalRequest);
        } catch (refreshError: any) {
          // The REFRESH call's own failure is what explains the sign-out, not the original 401 --
          // that one just says "your access token is stale", which is routine and expected here.
          // The refresh response is where the backend distinguishes an ordinary expiry from a
          // reused (suspected stolen) token that revoked every session.
          clearSessionAndRedirect(refreshError?.response?.data?.message);
          return Promise.reject(error);
        }
      } else {
        clearSessionAndRedirect();
      }
    }

    // Backend is the source of truth on phone verification (PhoneVerificationFilter) -- if a
    // request is rejected for this reason, the user has a valid session but hasn't completed
    // verification (e.g. they navigated straight to /app, bypassing the post-login redirect).
    // Send them to finish it rather than leaving every subsequent call silently failing.
    if (error.response?.status === 403 && error.response?.data?.errorCode === 'PHONE_VERIFICATION_REQUIRED') {
      if (!window.location.pathname.startsWith('/verify-phone')) {
        window.location.href = '/verify-phone';
      }
      return Promise.reject(error);
    }

    // Error responses use the same envelope ({success:false, message, errorCode}) —
    // surface the message where callers already expect err.response.data.message.
    if (error.response?.data?.message) {
      error.response.data = { message: error.response.data.message, errorCode: error.response.data.errorCode };
    }
    return Promise.reject(error);
  }
);
