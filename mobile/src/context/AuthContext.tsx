import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { authApi } from '../api/endpoints';
import { setSessionCallbacks } from '../api/client';
import { safeStorage } from '../lib/safeStorage';

/**
 * Ported from frontend/src/context/AuthContext.tsx -- same state shape, same method contracts.
 *
 * The one structural difference: the web version seeds every piece of state synchronously inside
 * useState initializers, because localStorage reads are synchronous. SecureStore's are not, so
 * this restores the session in an effect and exposes `bootstrapping` while that's in flight.
 * Without it, the navigator would see token === null on the very first render of a cold start and
 * flash the Login screen at an already-signed-in user before the real token arrived.
 */
interface AuthState {
  bootstrapping: boolean;
  token: string | null;
  email: string | null;
  fullName: string | null;
  phoneVerified: boolean;
  // Accepts either an email address or a registered mobile number -- see LoginScreen.
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

const TOKEN_KEY = 'finora_token';
const REFRESH_TOKEN_KEY = 'finora_refresh_token';
const EMAIL_KEY = 'finora_email';
const NAME_KEY = 'finora_name';
const PHONE_VERIFIED_KEY = 'finora_phone_verified';

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [bootstrapping, setBootstrapping] = useState(true);
  const [token, setToken] = useState<string | null>(null);
  const [email, setEmail] = useState<string | null>(null);
  const [fullName, setFullName] = useState<string | null>(null);
  const [phoneVerified, setPhoneVerifiedState] = useState(false);

  // Restore a persisted session on cold start. Reads run in parallel -- they're independent keys,
  // and on Android each SecureStore read is a separate bridge round-trip.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      const [storedToken, storedEmail, storedName, storedVerified] = await Promise.all([
        safeStorage.getItem(TOKEN_KEY),
        safeStorage.getItem(EMAIL_KEY),
        safeStorage.getItem(NAME_KEY),
        safeStorage.getItem(PHONE_VERIFIED_KEY),
      ]);
      if (cancelled) return;
      setToken(storedToken);
      setEmail(storedEmail);
      setFullName(storedName);
      setPhoneVerifiedState(storedVerified === 'true');
      setBootstrapping(false);
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  // Clears in-memory state only. Used by the API client's session-expiry callback, which has
  // already cleared storage itself -- re-clearing it here would be redundant work on a path
  // that's already handling a failure.
  function clearLocalState() {
    setToken(null);
    setEmail(null);
    setFullName(null);
    setPhoneVerifiedState(false);
  }

  // The API client can't import navigation or this context (it's imported BY both), so it calls
  // back into here instead. Driving auth state is enough to redirect: RootNavigator picks its
  // stack from `token`/`phoneVerified`, so clearing the token lands on Login and clearing the
  // verified flag lands on VerifyPhone -- no imperative navigation call needed.
  useEffect(() => {
    setSessionCallbacks({
      onSessionExpired: clearLocalState,
      onPhoneVerificationRequired: () => setPhoneVerifiedState(false),
    });
  }, []);

  async function persist(data: {
    token: string;
    refreshToken: string;
    email: string;
    fullName: string;
    phoneVerified: boolean;
  }) {
    await Promise.all([
      safeStorage.setItem(TOKEN_KEY, data.token),
      safeStorage.setItem(REFRESH_TOKEN_KEY, data.refreshToken),
      safeStorage.setItem(EMAIL_KEY, data.email),
      safeStorage.setItem(NAME_KEY, data.fullName),
      safeStorage.setItem(PHONE_VERIFIED_KEY, String(data.phoneVerified)),
    ]);
    setToken(data.token);
    setEmail(data.email);
    setFullName(data.fullName);
    setPhoneVerifiedState(data.phoneVerified);
  }

  // Returns whether the phone is already verified. Unlike the web app, callers don't navigate on
  // this -- RootNavigator switches stacks off the same state persist() just set. It's still
  // returned because it reads naturally at the call site and mirrors the web contract.
  async function login(identifier: string, password: string): Promise<boolean> {
    const res = await authApi.login(identifier, password);
    // res.data.email is always the account's real email, regardless of whether the user typed
    // their email or phone number -- nothing downstream needs to know which was used.
    await persist(res.data);
    return res.data.phoneVerified;
  }

  async function register(
    regEmail: string,
    password: string,
    name: string,
    phoneNumber: string
  ): Promise<{ phoneVerified: boolean }> {
    const res = await authApi.register(regEmail, password, name, phoneNumber);
    await persist(res.data);
    // VerifyPhoneScreen fetches the account's real phone number itself (userApi.get(), now that
    // it's authenticated) rather than being handed it through navigation params.
    return { phoneVerified: res.data.phoneVerified };
  }

  function setPhoneVerified(verified: boolean) {
    void safeStorage.setItem(PHONE_VERIFIED_KEY, String(verified));
    setPhoneVerifiedState(verified);
  }

  function logout() {
    // Clear local state first so the UI responds immediately -- the user expects to be signed out
    // whether or not the network call lands.
    clearLocalState();
    void (async () => {
      // Best-effort: revoke the refresh token server-side so it can't be reused even if someone
      // captured it. Read before removal, since removal would otherwise race this read.
      const refreshToken = await safeStorage.getItem(REFRESH_TOKEN_KEY);
      if (refreshToken) {
        authApi.logout(refreshToken).catch(() => {});
      }
      await Promise.all([
        safeStorage.removeItem(TOKEN_KEY),
        safeStorage.removeItem(REFRESH_TOKEN_KEY),
        safeStorage.removeItem(EMAIL_KEY),
        safeStorage.removeItem(NAME_KEY),
        safeStorage.removeItem(PHONE_VERIFIED_KEY),
      ]);
    })();
  }

  return (
    <AuthContext.Provider
      value={{ bootstrapping, token, email, fullName, phoneVerified, login, register, setPhoneVerified, logout }}
    >
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
