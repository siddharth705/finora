import axios from 'axios';
import { safeStorage } from '../lib/safeStorage';

// Bug fix (production-readiness pass): same issue as the user frontend's client.ts (see that
// file's own doc comment for the full story) -- vite.config.ts's server.proxy only applies to
// `vite dev`, never to the actual production build, so a bare relative '/api/v1' silently stops
// working the moment this is built and deployed to its own origin (Cloudflare, in Finora's
// current deployment) separate from the Railway backend. This app already has
// VITE_BACKEND_ORIGIN for Diagnostics.tsx's direct Swagger/Actuator links (which can't use the
// proxy either) -- this is the same gap, just for the axios client itself rather than a couple
// of manually-constructed links.
//
// Second bug fix, caught from an actual production CORS error on the user-frontend side of this
// same class: VITE_API_BASE_URL got set to the bare Railway origin without the /api/v1 backend
// routes actually live under, so every request silently lost that path segment. normalizeApiBase
// makes this correct either way -- whether the env var is the bare origin or already includes
// /api/v1, the result always has exactly one /api/v1 suffix.
export function normalizeApiBase(rawBase: string): string {
  const trimmed = rawBase.replace(/\/+$/, '');
  return trimmed.endsWith('/api/v1') ? trimmed : `${trimmed}/api/v1`;
}

const BASE_URL = import.meta.env.VITE_API_BASE_URL
  ? normalizeApiBase(import.meta.env.VITE_API_BASE_URL)
  : '/api/v1';

// withCredentials: true so RefreshTokenCookie actually functions -- see the matching comment in
// frontend/src/api/client.ts. Axios defaults it to false, so the HttpOnly refresh cookie the
// backend sets was neither stored nor sent on this app's cross-origin deployment, and
// CorsConfig's allowCredentials(true) had nothing to permit.
//
// BH-012 (admin portal): this app used to ALSO keep its own copy of the refresh token in
// localStorage (finora_admin_refresh_token) and send it explicitly on every refresh/logout call,
// even though withCredentials was already true and the backend was already issuing the same token
// as an HttpOnly cookie. That meant the durable, 30-day credential existed in two places at once
// -- one an XSS on this origin cannot read, and one it can read with a single `localStorage.
// getItem()` call. The user-facing frontend (frontend/src/api/client.ts) was fixed for exactly
// this reason; the admin portal, which is at least as sensitive, was never brought in line with
// it. The localStorage copy is now gone -- see clearAdminSession()/persistAdminSession() below --
// so this withCredentials flag is what the refresh token's transport actually depends on now, not
// merely benefits from.
export const api = axios.create({ baseURL: BASE_URL, withCredentials: true });

// A separate, interceptor-free instance for the /auth/refresh call itself -- same reasoning as
// the user frontend's rawApi (see finora/frontend/src/api/client.ts): if the refresh call went
// through `api`'s own response interceptor and also got a 401, it would recursively trigger
// another refresh attempt.
export const rawApi = axios.create({ baseURL: BASE_URL, withCredentials: true });

// SEC-01 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). Same fix as the user
// frontend's client.ts (see that file's own comment) -- the access token moves out of storage and
// into a module-level variable, for the same reason BH-012 already moved the refresh token out:
// an XSS on this origin can still read anything in localStorage, and an admin session is at least
// as sensitive as an ordinary user's. AdminAuthContext.tsx's own mount-time bootstrap effect is
// the other half (recovering a token after a reload via the HttpOnly refresh cookie).
let accessToken: string | null = null;
export function getAdminToken(): string | null {
  return accessToken;
}
export function setAdminToken(token: string | null): void {
  accessToken = token;
}

export interface ApiEnvelope<T> {
  success: boolean;
  message: string;
  data: T;
  timestamp: string;
  errorCode: string | null;
  requestId: string | null;
}


// D-23/D-26: /auth/google and /auth/apple listed here even though this app never calls either --
// scripts/check-client-auth-policy.py requires all three API clients (frontend, admin-portal,
// mobile) to agree on this list entry-for-entry, treating it as a declared policy rather than a
// per-app usage log. See frontend/src/api/client.ts's own copy of this comment for the real bug
// this check exists to prevent.
const AUTH_ENDPOINTS_NO_TOKEN = ['/auth/login', '/auth/register', '/auth/refresh', '/auth/forgot-password', '/auth/reset-password', '/auth/reactivate', '/auth/google', '/auth/apple'];

api.interceptors.request.use((config) => {
  const isAuthEndpoint = AUTH_ENDPOINTS_NO_TOKEN.some((path) => config.url?.includes(path));
  if (!isAuthEndpoint) {
    const token = getAdminToken();
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
  }
  return config;
});

export function clearAdminSession() {
  setAdminToken(null);
  // BH-012: no refresh-token key to remove here anymore -- it is never written (see
  // persistAdminSession() below), and any leftover 'finora_admin_refresh_token' from a
  // pre-migration session is inert going forward since nothing reads it. Left alone rather than
  // explicitly cleaned up, matching frontend/src/api/client.ts's equivalent clear function.
}

/**
 * Where a forced-sign-out reason waits for the login page to pick it up. Mirrors the user app's
 * SESSION_ENDED_REASON_KEY exactly (frontend/src/api/client.ts) -- same problem, same shape, so the
 * two apps behave identically when a session ends under someone.
 *
 * The redirect below is a FULL page navigation, so React unmounts and any in-memory or router state
 * goes with it. Without this handoff the backend's explanation was simply dropped: an admin got
 * bounced to the login screen with no indication of whether their session merely expired or every
 * session was revoked after a suspected stolen token.
 */
export const ADMIN_SESSION_ENDED_REASON_KEY = 'finora_admin_session_ended_reason';

/**
 * Deliberately separate from clearAdminSession(), which is also called for an ordinary user-
 * initiated logout -- that case should NOT leave a "your session ended" notice behind, because
 * nothing unexpected happened and the admin already knows why they're back at the login screen.
 */
function endSessionAndRedirect(reason?: string) {
  clearAdminSession();
  safeStorage.setItem(ADMIN_SESSION_ENDED_REASON_KEY,
    reason || 'Your session has ended. Please sign in again to continue.');
  window.location.href = '/login';
}

export function persistAdminSession(token: string) {
  // SEC-01: in-memory only now -- see the accessToken variable and getAdminToken/setAdminToken
  // near the top of this file.
  setAdminToken(token);
  // BH-012: the refresh token is deliberately NOT stored here. It arrives as an HttpOnly cookie
  // the browser keeps out of script's reach (see the withCredentials comment above); writing a
  // second copy into storage any XSS can read is what made that cookie decorative. The field
  // stays on the response because mobile clients, which have no cookie jar, genuinely need it.
}

function unwrapEnvelope(response: any) {
  if (response.data && typeof response.data === 'object' && 'success' in response.data) {
    response.data = response.data.data;
  }
  return response;
}

// Bug fix: same class of bug as the user frontend's client.ts (see that file's own doc comment
// for the full story) -- refresh tokens rotate server-side on every use, and reusing an
// already-rotated one is treated as a THEFT signal that revokes every active session for the
// admin, everywhere. Without this shared promise, N requests 401'ing around the same moment (the
// access token expiring while idle, then several widgets refetching at once) each independently
// called authApi.refresh() -- only the first succeeds; the rest trip the backend's theft response
// over a client-side race, not actual theft.
let refreshInFlight: Promise<{ token: string; refreshToken: string }> | null = null;

// BH-012: takes no refresh token argument -- there is nothing left for a caller to pass. The
// cookie travels with the request automatically (withCredentials: true); authApi.refresh() sends
// no body at all now (see endpoints.ts). Matches frontend/src/api/client.ts's
// refreshAccessToken() exactly, minus that app's cross-tab Web Locks coordination, which is a
// separate concern (BH-013) out of scope for this migration.
//
// Bug fix: exported so AdminAuthContext.tsx's own bootstrap effect can call this instead of
// authApi.refresh() directly -- see frontend/src/api/client.ts's matching refreshAccessToken()
// comment for why a raw call there is a real bug. Same shape here: React.StrictMode
// double-invokes that effect on every real mount, and its cleanup only gates the state updates
// that follow, not the network request the first invocation already sent -- so a raw
// authApi.refresh() call raced two real requests against the same not-yet-rotated refresh token.
// This function's refreshInFlight de-dupe (this file's own comment above) closes that within one
// tab; it does not add BH-013's cross-tab lock, which stays the deliberately separate, deferred
// concern this comment already names.
export function refreshAccessToken(): Promise<{ token: string; refreshToken: string }> {
  if (!refreshInFlight) {
    refreshInFlight = (async () => {
      const { authApi } = await import('./endpoints');
      return authApi.refresh();
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
    // A 401 from login/register itself means "these credentials are wrong," not "this session
    // expired" -- treating it as the latter (attempting a token refresh, then hard-redirecting to
    // /login on failure) was a real bug: on a fresh browser with no stored refresh token, this
    // fired on every wrong-password attempt, wiping out Login.tsx's own inline error message with
    // a jarring full-page reload before it could ever render.
    const isAuthEndpoint = AUTH_ENDPOINTS_NO_TOKEN.some((path) => originalRequest.url?.includes(path));

    if (error.response?.status === 401 && !originalRequest._retried && !isAuthEndpoint) {
      originalRequest._retried = true;
      // BH-012: no refresh token to read from storage anymore -- what is checked here is whether
      // this browser believes it has a session at all. With no access token there is nothing to
      // refresh, and attempting one would turn every anonymous request into a pointless round
      // trip. Mirrors frontend/src/api/client.ts's identical staleToken check.
      const staleToken = getAdminToken();

      if (staleToken) {
        try {
          const refreshed = await refreshAccessToken();
          persistAdminSession(refreshed.token);
          originalRequest.headers.Authorization = `Bearer ${refreshed.token}`;
          return api(originalRequest);
        } catch (refreshError: any) {
          // The REFRESH call's own failure is what explains the sign-out, not the original 401 --
          // that one only says "your access token is stale", which is routine here. The refresh
          // response is where the backend distinguishes an ordinary expiry (AUTH_002) from a reused
          // token that revoked every session as a theft precaution (AUTH_004).
          endSessionAndRedirect(refreshError?.response?.data?.message);
          return Promise.reject(error);
        }
      } else {
        endSessionAndRedirect();
      }
    }

    // Backend is the source of truth on phone verification (PhoneVerificationFilter), which 403s
    // this code for EVERY non-excluded endpoint, not just /users/me/access. The user frontend's
    // interceptor has always had this branch; this one did not, so the admin portal handled the
    // condition in exactly one place -- AdminAuthContext.loadAccess(), which runs on mount and
    // after login. A session that outlives its verification, or an admin whose verification is
    // revoked mid-session, therefore got a silent 403 on every subsequent call: pages rendered
    // empty or stuck loading, with the one actionable error code in the response discarded.
    if (error.response?.status === 403 && error.response?.data?.errorCode === 'PHONE_VERIFICATION_REQUIRED') {
      if (!window.location.pathname.startsWith('/verify-phone')) {
        window.location.href = '/verify-phone';
      }
      return Promise.reject(error);
    }

    // Error responses use the same {success:false, message, errorCode} envelope as the user
    // frontend's backend calls -- surface message/errorCode where callers expect them.
    if (error.response?.data?.message) {
      error.response.data = { message: error.response.data.message, errorCode: error.response.data.errorCode };
    }
    return Promise.reject(error);
  }
);
