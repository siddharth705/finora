import axios from 'axios';

const BASE_URL = '/api/v1';

export const api = axios.create({ baseURL: BASE_URL });

// A separate, interceptor-free instance for the /auth/refresh call itself -- same reasoning as
// the user frontend's rawApi (see finora/frontend/src/api/client.ts): if the refresh call went
// through `api`'s own response interceptor and also got a 401, it would recursively trigger
// another refresh attempt.
export const rawApi = axios.create({ baseURL: BASE_URL });

export interface ApiEnvelope<T> {
  success: boolean;
  message: string;
  data: T;
  timestamp: string;
  errorCode: string | null;
  requestId: string | null;
}

// Deliberately distinct localStorage keys from the user frontend (finora_token vs.
// finora_admin_token) -- the two apps run on different ports/origins so browser storage is
// already isolated per-origin, but distinct names make it unambiguous in devtools which app's
// session you're looking at, and avoid any confusion if the two apps are ever served from the
// same origin in a future deployment.
const TOKEN_KEY = 'finora_admin_token';
const REFRESH_TOKEN_KEY = 'finora_admin_refresh_token';

const AUTH_ENDPOINTS_NO_TOKEN = ['/auth/login', '/auth/register', '/auth/refresh', '/auth/forgot-password', '/auth/reset-password'];

api.interceptors.request.use((config) => {
  const isAuthEndpoint = AUTH_ENDPOINTS_NO_TOKEN.some((path) => config.url?.includes(path));
  if (!isAuthEndpoint) {
    const token = localStorage.getItem(TOKEN_KEY);
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
  }
  return config;
});

export function clearAdminSession() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(REFRESH_TOKEN_KEY);
}

export function persistAdminSession(token: string, refreshToken: string) {
  localStorage.setItem(TOKEN_KEY, token);
  localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken);
}

export function getAdminRefreshToken(): string | null {
  return localStorage.getItem(REFRESH_TOKEN_KEY);
}

export function getAdminToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

function unwrapEnvelope(response: any) {
  if (response.data && typeof response.data === 'object' && 'success' in response.data) {
    response.data = response.data.data;
  }
  return response;
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
      const refreshToken = getAdminRefreshToken();

      if (refreshToken) {
        try {
          const { authApi } = await import('./endpoints');
          const refreshed = await authApi.refresh(refreshToken);
          persistAdminSession(refreshed.token, refreshed.refreshToken);
          originalRequest.headers.Authorization = `Bearer ${refreshed.token}`;
          return api(originalRequest);
        } catch {
          clearAdminSession();
          window.location.href = '/login';
          return Promise.reject(error);
        }
      } else {
        clearAdminSession();
        window.location.href = '/login';
      }
    }

    // Error responses use the same {success:false, message, errorCode} envelope as the user
    // frontend's backend calls -- surface message/errorCode where callers expect them.
    if (error.response?.data?.message) {
      error.response.data = { message: error.response.data.message, errorCode: error.response.data.errorCode };
    }
    return Promise.reject(error);
  }
);
