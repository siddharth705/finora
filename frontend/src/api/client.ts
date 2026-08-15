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
// BH-012: the localStorage copy is GONE, and with it the reason the cookie was inert. This
// comment used to end "Removing the localStorage copy is the other half ... Restoring the
// transport first is what makes that half possible" -- that is what this change is.
//
// The refresh token is now held only in the HttpOnly cookie, which script cannot read. An XSS on
// this origin can still steal the 15-minute access token; it can no longer walk away with a
// 30-day rotating credential that survives the tab closing.
//
// DEPLOYMENT PRECONDITION, because this now depends on it rather than merely benefiting from it:
// the cookie is Secure, SameSite=Lax and host-only, scoped to /api/v1/auth. It reaches the API
// only when the app and the API share a registrable domain (app.finoratech.info /
// api.finoratech.info). Point VITE_API_BASE_URL at a different site -- a *.pages.dev or a bare
// *.up.railway.app -- and the browser will not attach it, refresh will fail, and users will be
// signed out every 15 minutes. That was survivable before because localStorage papered over it.
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
const AUTH_ENDPOINTS_NO_TOKEN = ['/auth/login', '/auth/register', '/auth/refresh', '/auth/forgot-password', '/auth/reset-password', '/auth/reactivate'];

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

/**
 * Exported (not just used internally by the interceptor below) specifically so any flow that needs
 * to end the session and explain why -- outside of a 401 -- can call the one real implementation
 * instead of re-deriving it. Settings.tsx's account-deactivation flow is the first such caller: it
 * needs the exact same "clear storage without touching AuthContext's React state, so
 * ProtectedRoute's own reactive redirect can't race a component that never mounts, then hard-
 * navigate" behavior this function already provides for session-expiry. A second hand-written copy
 * of the four `finora_*` keys below already caused a bug once (see the comment inside) -- this
 * export exists so a third one doesn't.
 */
export function clearSessionAndRedirect(reason?: string) {
  // Mirrors every key AuthContext.logout() clears -- this used to miss finora_phone_verified,
  // leaving that one flag behind in localStorage after a forced session expiry. Currently
  // inert in practice (ProtectedRoute redirects on a missing token before it would ever read
  // this flag), but it's a real hygiene gap: a stale, unrelated-to-the-next-session value left
  // sitting in storage is exactly the kind of thing that turns into a real bug the moment some
  // future feature reads phoneVerified independently of token presence.
  safeStorage.removeItem('finora_token');
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

// Refresh tokens rotate server-side on every use (RefreshTokenService.rotate()) -- presenting an
// already-rotated token isn't just rejected, it's treated as a THEFT signal and revokes every
// active session for the user, everywhere. So two refreshes with the same token do not merely
// waste a request; they sign the user out on their laptop AND their phone.
//
// This promise de-duplicates concurrent 401s WITHIN one tab: the access token expires while the
// tab is idle, several components refetch on focus, and each would otherwise start its own
// refresh.
let refreshInFlight: Promise<string> | null = null;

// BH-013. The in-tab promise above is not enough, and the gap is not exotic -- it is two open
// tabs, which is ordinary use of a financial dashboard.
//
// Each tab is its own JavaScript context with its own module instance, so `refreshInFlight` is
// invisible across them. Two idle tabs both wake, both 401, both refresh: one wins, the other
// presents a token the server has just rotated, and reuse detection concludes the credential was
// stolen and revokes every session on every device. The user is bounced out everywhere and shown
// "All sessions have been signed out as a precaution" -- for having two tabs open. Repeated
// often enough, that message stops meaning anything on the day it is real.
//
// navigator.locks is the right primitive rather than a localStorage mutex: it is same-origin and
// cross-tab by definition, and the lock is released automatically if the holding tab is closed or
// crashes mid-refresh -- a hand-rolled mutex has to invent a timeout for that case and then gets
// to choose between deadlocking and reintroducing the race.
//
// The re-check inside the lock is the half that actually prevents the second refresh. Waiting for
// the lock and then refreshing anyway would just serialise the two calls and still present the
// rotated token. So the loser compares the access token it set out with against what is stored
// now: if another tab has already rotated, that work is done and the stored token is the answer.
async function refreshAccessToken(staleToken: string | null): Promise<string> {
  if (!refreshInFlight) {
    refreshInFlight = withCrossTabLock(async () => {
      const current = safeStorage.getItem('finora_token');
      if (current && current !== staleToken) {
        // Another tab refreshed while this one waited. Nothing to do.
        return current;
      }
      const { authApi } = await import('./endpoints');
      const refreshed = await authApi.refresh();
      safeStorage.setItem('finora_token', refreshed.token);
      return refreshed.token;
    }).finally(() => {
      refreshInFlight = null;
    });
  }
  return refreshInFlight;
}

/** Serialises across tabs where the Web Locks API exists, and degrades to running directly where
 *  it does not -- an old browser gets today's behaviour rather than a broken sign-in. */
function withCrossTabLock<T>(work: () => Promise<T>): Promise<T> {
  if (typeof navigator !== 'undefined' && navigator.locks?.request) {
    return navigator.locks.request('finora-token-refresh', work) as Promise<T>;
  }
  return work();
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
      // BH-012: the refresh token is no longer read from storage, because it is no longer PUT
      // there -- it travels only as the HttpOnly cookie. What is checked here is whether this
      // browser believes it has a session at all: with no access token there is nothing to
      // refresh, and attempting one would turn every anonymous request into a pointless round
      // trip. The access token also doubles as the staleness marker the cross-tab lock compares
      // against.
      const staleToken = safeStorage.getItem('finora_token');

      if (staleToken) {
        try {
          const freshToken = await refreshAccessToken(staleToken);
          originalRequest.headers.Authorization = `Bearer ${freshToken}`;
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

    // Error responses use the same envelope ({success:false, message, errorCode, details}) —
    // surface the message where callers already expect err.response.data.message. `details` is
    // carried through too (not just message/errorCode): AUTH_ACCOUNT_DEACTIVATED's reactivation
    // token travels there (see ApiException/ApiResponse on the backend) and this used to drop it
    // silently, which would have made the reactivation flow unreachable from the browser.
    if (error.response?.data?.message) {
      error.response.data = {
        message: error.response.data.message,
        errorCode: error.response.data.errorCode,
        details: error.response.data.details,
        // Sprint 4 item 22: whether the user themselves can fix what caused this (a password
        // panel needing a password is the clearest case) -- computed once, backend-side, from
        // ErrorCode.userActionRequired() (GlobalExceptionHandler), not re-derived here. Absent
        // (undefined) for a codeless ApiException, which has no classification to offer; callers
        // treat that the same as false, never guessing a failure into looking actionable.
        userActionRequired: error.response.data.details?.userActionRequired,
      };
    }
    return Promise.reject(error);
  }
);
