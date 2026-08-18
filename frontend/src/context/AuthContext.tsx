import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { authApi, userApi } from '../api/endpoints';
import { AUTH_CHANGED_EVENT } from './ThemeContext';
import { safeStorage } from '../lib/safeStorage';
import { getAccessToken, setAccessToken } from '../api/client';

interface AuthState {
  token: string | null;
  // SEC-01: true only for the brief window between mount and the bootstrap effect below
  // resolving. ProtectedRoute waits on this rather than treating a not-yet-populated token the
  // same as a logged-out one -- see that component's own comment.
  bootstrapping: boolean;
  email: string | null;
  fullName: string | null;
  phoneVerified: boolean;
  // Accepts either an email address or a registered mobile number -- see Login.tsx.
  login: (identifier: string, password: string) => Promise<boolean>;
  // Completes the "Welcome back — reactivate your account?" prompt Login.tsx shows after a
  // deactivated account's password checks out -- see ReactivateAccountPrompt.tsx.
  reactivate: (token: string) => Promise<boolean>;
  register: (
    email: string,
    password: string,
    fullName: string,
    phoneNumber: string
  ) => Promise<{ phoneVerified: boolean }>;
  // Same shape as login()/reactivate(): persists the session and reports whether the phone is
  // already verified -- true for an auto-linked existing account, always false for a newly
  // created one (Google sign-in never carries a phone number; see D-23).
  loginWithGoogle: (idToken: string) => Promise<boolean>;
  setPhoneVerified: (verified: boolean) => void;
  logout: () => void;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  // SEC-01 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). Used to seed this
  // from safeStorage.getItem('finora_token') -- that copy is gone now (client.ts's in-memory
  // accessToken variable is the only place the token lives), so a fresh page load genuinely starts
  // with nothing here. The bootstrap effect below is what turns "nothing yet" into "logged out" or
  // "logged in" before anything downstream has to guess.
  const [token, setToken] = useState<string | null>(null);
  const [bootstrapping, setBootstrapping] = useState(true);
  const [email, setEmail] = useState<string | null>(safeStorage.getItem('finora_email'));
  const [fullName, setFullName] = useState<string | null>(safeStorage.getItem('finora_name'));
  // Defaults to true when there's no stored value, matching AdminAuthContext -- which already
  // carries the reasoning this one was missing: a real `false` is only ever written by
  // login()/persist() once the backend has actually said so, so a MISSING key means "we don't
  // know", and treating that as "not verified" bounces an already-verified session to
  // /verify-phone with no client-side way out. `=== 'true'` made absence mean false.
  //
  // Reachable in ordinary use, not just on an upgrade from a pre-field session: safeStorage
  // silently no-ops on write failure by design, and persist() writes its storage keys in sequence,
  // so a quota failure partway through can leave finora_email/finora_name stored and
  // finora_phone_verified absent (SEC-01 moved the token itself out of this sequence entirely --
  // see the module-level accessToken variable in client.ts -- but the same partial-write risk
  // still applies to whichever storage keys persist() writes after it). ProtectedRoute then
  // redirects on this flag alone, before any backend round-trip.
  //
  // The backend remains the source of truth either way -- PhoneVerificationFilter 403s a genuinely
  // unverified user on every other endpoint, and client.ts's interceptor turns that into the
  // redirect. Guessing "verified" wrong costs one rejected request; guessing "unverified" wrong
  // costs the user their session.
  const [phoneVerified, setPhoneVerifiedState] = useState<boolean>(
    safeStorage.getItem('finora_phone_verified') !== 'false'
  );

  function persist(data: { token: string; refreshToken: string; email: string; fullName: string; phoneVerified: boolean }) {
    // SEC-01: in-memory only now -- see client.ts's accessToken variable and this file's own
    // bootstrap effect for the other half (recovering it after a reload).
    setAccessToken(data.token);
    // BH-012: data.refreshToken is deliberately NOT stored. The same token arrives as an HttpOnly
    // cookie the browser keeps out of script's reach, and writing a second copy here into storage
    // any XSS can read is what made that cookie decorative. The field stays on the response
    // because mobile -- which has no cookie jar -- genuinely needs it.
    safeStorage.setItem('finora_email', data.email);
    safeStorage.setItem('finora_name', data.fullName);
    safeStorage.setItem('finora_phone_verified', String(data.phoneVerified));
    setToken(data.token);
    setEmail(data.email);
    setFullName(data.fullName);
    setPhoneVerifiedState(data.phoneVerified);
    // Lets ThemeProvider (mounted above AuthProvider, so it can't consume this state directly)
    // re-pull the account's saved theme now that a token exists, instead of only ever checking
    // for one once on its own initial mount.
    window.dispatchEvent(new Event(AUTH_CHANGED_EVENT));
  }

  // Returns whether the phone is already verified — callers use this to decide whether to
  // route through the OTP verification screen or straight into the app.
  async function login(identifier: string, password: string): Promise<boolean> {
    const res = await authApi.login(identifier, password);
    // res.data.email is always the account's real email address, regardless of whether the
    // user typed their email or their phone number to log in -- nothing downstream needs to
    // know which identifier was actually used.
    persist(res.data);
    return res.data.phoneVerified;
  }

  // Same shape as login(): persists the session and reports whether the phone is already
  // verified, so the caller can route the same way login()'s caller does.
  async function reactivate(token: string): Promise<boolean> {
    const res = await authApi.reactivate(token);
    persist(res.data);
    return res.data.phoneVerified;
  }

  async function register(
    regEmail: string,
    password: string,
    name: string,
    phoneNumber: string
  ): Promise<{ phoneVerified: boolean }> {
    const res = await authApi.register(regEmail, password, name, phoneNumber);
    persist(res.data);
    // VerifyPhone.tsx fetches the account's real phone number itself (userApi.get(), now that
    // it's authenticated) rather than being handed it via router state -- one less thing this
    // return value needs to carry.
    return { phoneVerified: res.data.phoneVerified };
  }

  async function loginWithGoogle(idToken: string): Promise<boolean> {
    const res = await authApi.google(idToken);
    persist(res.data);
    return res.data.phoneVerified;
  }

  function setPhoneVerified(verified: boolean) {
    safeStorage.setItem('finora_phone_verified', String(verified));
    setPhoneVerifiedState(verified);
  }

  function logout() {
    // Best-effort: revoke the refresh token server-side so it can't be used again even if
    // someone captured it. Don't block clearing local state on this succeeding — if the
    // network call fails, the user still expects to be logged out locally.
    // The cookie is what identifies the session to revoke, and the browser attaches it
    // automatically -- there is nothing for this call to carry. Still gated on believing there IS
    // a session: the access token is the proxy for that now (client.ts's interceptor uses the same
    // one), where this used to gate on holding a readable refresh token. Logging out when nobody
    // is logged in should stay a local no-op rather than a pointless request.
    if (getAccessToken()) {
      authApi.logout().catch(() => {});
    }
    setAccessToken(null);
    safeStorage.removeItem('finora_email');
    safeStorage.removeItem('finora_name');
    safeStorage.removeItem('finora_phone_verified');
    setToken(null);
    setEmail(null);
    setFullName(null);
    setPhoneVerifiedState(false);
  }

  // SEC-01 bootstrap. The access token no longer survives a reload (it's in-memory only, see
  // client.ts), but the HttpOnly refresh cookie does -- so a returning, still-logged-in user is
  // recovered by attempting one silent refresh on mount, then filling in the profile fields
  // persist() would normally have gotten from a login/register response. /auth/refresh itself only
  // returns {token, refreshToken} (see AuthDtos.RefreshResponse -- deliberately minimal, since
  // mobile's own equivalent call needs nothing more), so userApi.get() is the follow-up call for
  // everything else this context exposes.
  //
  // A failure here (no cookie, or an expired/already-consumed one) is the ordinary "not logged in"
  // case for a first visit or a genuinely ended session -- not logged or surfaced as an error.
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const refreshed = await authApi.refresh();
        if (cancelled) return;
        setAccessToken(refreshed.token);
        const profile = await userApi.get();
        if (cancelled) return;
        setToken(refreshed.token);
        setEmail(profile.email);
        setFullName(profile.fullName);
        setPhoneVerifiedState(profile.phoneVerified);
        safeStorage.setItem('finora_email', profile.email);
        safeStorage.setItem('finora_name', profile.fullName);
        safeStorage.setItem('finora_phone_verified', String(profile.phoneVerified));
        window.dispatchEvent(new Event(AUTH_CHANGED_EVENT));
      } catch {
        // No valid session to recover -- leave every field at its logged-out default.
      } finally {
        if (!cancelled) setBootstrapping(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <AuthContext.Provider value={{ token, bootstrapping, email, fullName, phoneVerified, login, reactivate, register, loginWithGoogle, setPhoneVerified, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
