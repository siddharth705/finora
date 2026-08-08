import { createContext, useContext, useState, type ReactNode } from 'react';
import { authApi } from '../api/endpoints';
import { AUTH_CHANGED_EVENT } from './ThemeContext';
import { safeStorage } from '../lib/safeStorage';

interface AuthState {
  token: string | null;
  email: string | null;
  fullName: string | null;
  phoneVerified: boolean;
  // Accepts either an email address or a registered mobile number -- see Login.tsx.
  login: (identifier: string, password: string) => Promise<boolean>;
  register: (
    email: string,
    password: string,
    fullName: string,
    phoneNumber: string
  ) => Promise<{ phoneVerified: boolean }>;
  setPhoneVerified: (verified: boolean) => void;
  logout: () => void;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setToken] = useState<string | null>(safeStorage.getItem('finora_token'));
  const [email, setEmail] = useState<string | null>(safeStorage.getItem('finora_email'));
  const [fullName, setFullName] = useState<string | null>(safeStorage.getItem('finora_name'));
  // Defaults to true when there's no stored value, matching AdminAuthContext -- which already
  // carries the reasoning this one was missing: a real `false` is only ever written by
  // login()/persist() once the backend has actually said so, so a MISSING key means "we don't
  // know", and treating that as "not verified" bounces an already-verified session to
  // /verify-phone with no client-side way out. `=== 'true'` made absence mean false.
  //
  // Reachable in ordinary use, not just on an upgrade from a pre-field session: safeStorage
  // silently no-ops on write failure by design, and persist() writes five keys in sequence, so a
  // quota failure partway through leaves finora_token stored and finora_phone_verified absent.
  // ProtectedRoute then redirects on this flag alone, before any backend round-trip.
  //
  // The backend remains the source of truth either way -- PhoneVerificationFilter 403s a genuinely
  // unverified user on every other endpoint, and client.ts's interceptor turns that into the
  // redirect. Guessing "verified" wrong costs one rejected request; guessing "unverified" wrong
  // costs the user their session.
  const [phoneVerified, setPhoneVerifiedState] = useState<boolean>(
    safeStorage.getItem('finora_phone_verified') !== 'false'
  );

  function persist(data: { token: string; refreshToken: string; email: string; fullName: string; phoneVerified: boolean }) {
    safeStorage.setItem('finora_token', data.token);
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
    if (safeStorage.getItem('finora_token')) {
      authApi.logout().catch(() => {});
    }
    safeStorage.removeItem('finora_token');
    safeStorage.removeItem('finora_email');
    safeStorage.removeItem('finora_name');
    safeStorage.removeItem('finora_phone_verified');
    setToken(null);
    setEmail(null);
    setFullName(null);
    setPhoneVerifiedState(false);
  }

  return (
    <AuthContext.Provider value={{ token, email, fullName, phoneVerified, login, register, setPhoneVerified, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
