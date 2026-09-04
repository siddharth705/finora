import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { AppState, type AppStateStatus } from 'react-native';
import { useQueryClient } from '@tanstack/react-query';
import { authApi } from '../api/endpoints';
import { setSessionCallbacks } from '../api/client';
import { safeStorage } from '../lib/safeStorage';
import { clearPersistedNavigationState } from '../navigation/useNavigationStatePersistence';
import { clearPersistedQueryCache, pauseQueryPersistence } from '../api/queryClient';
import { signOutOfGoogle } from '../lib/googleSession';
import { registerDeviceToken, revokeDeviceToken } from '../lib/pushRegistration';

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
  // Completes the "Welcome back — reactivate your account?" prompt LoginScreen shows after a
  // deactivated account's password checks out -- see the web app's ReactivateAccountPrompt.tsx,
  // which this mirrors.
  reactivate: (token: string) => Promise<boolean>;
  register: (
    email: string,
    password: string,
    fullName: string,
    phoneNumber: string
  ) => Promise<{ phoneVerified: boolean }>;
  // D-23 Phase 2. Mirrors frontend/src/context/AuthContext.tsx's own loginWithGoogle exactly --
  // same contract, same persist() reuse.
  loginWithGoogle: (idToken: string) => Promise<boolean>;
  // D-26 (iOS only). fullName is optional because expo-apple-authentication only hands it to the
  // CALLER on the user's very first authorization for this app -- see GoogleSignInButton's sibling
  // AppleSignInButton for where it's actually captured.
  loginWithApple: (idToken: string, fullName?: string) => Promise<boolean>;
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
  // Requires AuthProvider to sit inside QueryClientProvider, which App.tsx already arranges.
  const queryClient = useQueryClient();
  const [bootstrapping, setBootstrapping] = useState(true);
  const [token, setToken] = useState<string | null>(null);
  const [email, setEmail] = useState<string | null>(null);
  const [fullName, setFullName] = useState<string | null>(null);
  const [phoneVerified, setPhoneVerifiedState] = useState(false);

  // Restore a persisted session on cold start. Reads run in parallel -- they're independent keys,
  // and on Android each SecureStore read is a separate bridge round-trip.
  //
  // Deliberately checks only whether a token is PRESENT, not whether it's still valid -- there is
  // no cheap local way to know that, and blocking here on a network round-trip before painting
  // anything is exactly the loading-state-first pattern Item B's cache persistence
  // (queryClient.ts's startQueryPersistence) exists to avoid. The accepted consequence: if a
  // session expires while the app is fully closed, the very next open can paint real, persisted
  // financial figures for a beat before the first live request 401s and clearLocalState redirects
  // to Login -- the worst case during that gap goes from an empty skeleton (pre-persistence) to a
  // stale-but-real number. That's the same "show existing data" tradeoff the whole initiative was
  // built around, not a gap specific to this effect; narrowing it would mean gating every screen on
  // a dedicated validity check this app has no cheap way to make faster than the request that's
  // going to fire anyway.
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

  /**
   * The single authenticated -> unauthenticated transition, and therefore where the cache is
   * cleared. Every exit converges here: `logout()` calls it directly, and the API client's
   * `clearSessionAndRedirect()` reaches it through the `onSessionExpired` callback registered
   * below -- the path taken by a refresh the server rejects, a missing refresh token, and any
   * forced expiry.
   *
   * The clear used to live in `logout()`, which covered exactly half of it. Signing out cleared
   * the cache; a session EXPIRING did not. The financial query keys carry no user identity --
   * ['dashboard-summary'], ['transactions'], ['accounts'] are the same keys for everybody -- and
   * React Query serves cached data synchronously on mount before refetching. So a user who was
   * ejected rather than choosing to leave stayed cached, and the next person to sign in on the
   * device was rendered their balances first. Nobody chooses an expiry, which made the unprotected
   * path the more likely of the two, and it lands on Login looking exactly like a clean sign-out.
   *
   * Keeping the clear at the convergence point rather than at each caller makes the invariant
   * structural -- auth state becoming unauthenticated clears the financial cache -- so a future
   * exit path inherits it instead of depending on whoever adds it remembering to.
   *
   * clear(), not a list of keys to remove: an allow-list goes stale the first time a screen adds a
   * query, and the failure mode of forgetting one is leaking someone's money.
   *
   * useCallback, because the effect below registers this once and would otherwise pin render #1's
   * closure over `queryClient`. That is correct today only because useQueryClient() returns a
   * stable instance for the life of the provider -- correct by accident, and the accident ends
   * with a clear running against a client nobody reads from. Declaring the dependency makes the
   * registration re-run if that ever stops holding.
   */
  const clearLocalState = useCallback(() => {
    setToken(null);
    setEmail(null);
    setFullName(null);
    setPhoneVerifiedState(false);
    // Must run BEFORE queryClient.clear() below -- see pauseQueryPersistence's own comment. It
    // stops the persister reacting to clear()'s own cache-removal events, which would otherwise
    // race clearPersistedQueryCache's disk delete and could resurrect the departing session's data.
    pauseQueryPersistence();
    queryClient.clear();
    // Same reasoning as queryClient.clear() just above: a persisted screen position is a smaller
    // leak than a balance, but the next person signing in on this device landing on wherever the
    // previous account last was is still a mistake worth ruling out at this single convergence
    // point rather than by remembering it at every exit path. Fire-and-forget, same as every other
    // AsyncStorage write in this app -- there is no UI waiting on this to resolve.
    void clearPersistedNavigationState();
    // Item B: same convergence-point reasoning as clearPersistedNavigationState just above, one
    // layer further down. queryClient.clear() (above) only empties the IN-MEMORY cache -- Item B's
    // AsyncStorage persistence (startQueryPersistence, api/queryClient.ts) means a copy of
    // whatever was cached at the last save also lives on disk. Without this, the next person to
    // sign in on this device would have their very first frame painted from the PREVIOUS
    // account's persisted balances. Fire-and-forget, same as every other AsyncStorage write here.
    void clearPersistedQueryCache();
    // The Google session goes with it, for the same reason and at the same point as the cache: a
    // credential the SDK still holds lets the next person press "Sign in with Google" and land in
    // the previous person's account without a picker ever appearing. Fire-and-forget -- the local
    // state above must not wait on a native call, and signOutOfGoogle never rejects.
    void signOutOfGoogle();
  }, [queryClient]);

  // The API client can't import navigation or this context (it's imported BY both), so it calls
  // back into here instead. Driving auth state is enough to redirect: RootNavigator picks its
  // stack from `token`/`phoneVerified`, so clearing the token lands on Login and clearing the
  // verified flag lands on VerifyPhone -- no imperative navigation call needed.
  useEffect(() => {
    setSessionCallbacks({
      onSessionExpired: clearLocalState,
      onPhoneVerificationRequired: () => setPhoneVerifiedState(false),
    });
  }, [clearLocalState]);

  /**
   * Task 14. Re-registers the device's push token on every foreground transition (backgrounded ->
   * active), for an already-authenticated, already-verified session -- NOT on cold start (the
   * initial mount of this provider, i.e. app launch/relaunch from a terminated state), which is
   * exactly what Task 14's brief warns against: prompting for notification permission before the
   * user has seen any value from the app is the standard way to earn a permanent denial that can
   * only be undone by sending them to OS settings.
   *
   * "Foreground" here means the AppState background/inactive -> active transition while the app is
   * still running in memory, distinct from the mount/bootstrap effect above -- same distinction
   * AppLockGate.tsx draws for its own re-lock check, including the same ref-based
   * previous-state comparison.
   *
   * This re-check matters for two real cases a one-time registration at login would miss: a user
   * who denied the OS prompt the first time and later changed their mind in Settings (iOS/Android
   * both surface that as a status change only visible the next time permission is asked), and a
   * token that silently expired or rotated while the app sat backgrounded long enough for
   * onTokenRefresh's listener to have been torn down with the rest of this component tree in a
   * prior session. registerDeviceToken() itself is a safe no-op to call repeatedly: it never
   * re-prompts once the OS has recorded an answer, and re-POSTing an unchanged token is idempotent
   * on the backend.
   */
  const appState = useRef(AppState.currentState);
  useEffect(() => {
    const subscription = AppState.addEventListener('change', (next: AppStateStatus) => {
      const cameToForeground = !!appState.current?.match(/inactive|background/) && next === 'active';
      appState.current = next;
      if (cameToForeground && token !== null && phoneVerified) {
        void registerDeviceToken();
      }
    });
    return () => subscription.remove();
  }, [token, phoneVerified]);

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

    // Task 14. A RETURNING, already-verified user signing back in (login/reactivate/Google/Apple)
    // has already earned this prompt in an earlier session -- register (or re-register, if
    // Firebase issued a new token since they were last signed in) right away. A brand-new or
    // not-yet-verified session (data.phoneVerified === false, e.g. straight out of register())
    // is deliberately NOT registered here: POST /device-tokens is not exempt in
    // PhoneVerificationFilter, so calling it before verification completes would just 403. That
    // case is instead handled by setPhoneVerified() below, the moment verification actually
    // finishes.
    if (data.phoneVerified) {
      void registerDeviceToken();
    }
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

  // Same shape as login(): persists the session and reports whether the phone is already
  // verified, so the caller can route the same way login()'s caller does.
  async function reactivate(token: string): Promise<boolean> {
    const res = await authApi.reactivate(token);
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

  async function loginWithGoogle(idToken: string): Promise<boolean> {
    const res = await authApi.google(idToken);
    await persist(res.data);
    return res.data.phoneVerified;
  }

  async function loginWithApple(idToken: string, fullName?: string): Promise<boolean> {
    const res = await authApi.apple(idToken, fullName);
    await persist(res.data);
    return res.data.phoneVerified;
  }

  function setPhoneVerified(verified: boolean) {
    void safeStorage.setItem(PHONE_VERIFIED_KEY, String(verified));
    setPhoneVerifiedState(verified);

    // Task 14. This is the hard sequencing constraint: /api/v1/device-tokens is NOT exempt in
    // PhoneVerificationFilter, so a logged-in-but-unverified user who hit registerDeviceToken()
    // would get 403 PHONE_VERIFICATION_REQUIRED, not a stored token. Hooking it here -- the exact
    // moment VerifyPhoneScreen's backend call confirms verification and flips this flag true --
    // instead of at login() is what makes a brand-new user's very first registration attempt land
    // after the endpoint is actually callable. It also happens to satisfy "not at cold start" for
    // free: reaching this point requires a full register-then-OTP flow, never bare app launch.
    if (verified) {
      void registerDeviceToken();
    }
  }

  function logout() {
    // Clear local state first so the UI responds immediately -- the user expects to be signed out
    // whether or not the network call lands.
    clearLocalState();

    // The cache goes with it -- cleared by clearLocalState() above rather than here, so that a
    // session which expires gets the same guarantee as one that is signed out of. See its comment.
    void (async () => {
      // Task 14. Must run BEFORE the TOKEN_KEY removal further down, and awaited here rather than
      // fired in parallel with it: revokeDeviceToken()'s POST /device-tokens/revoke call needs the
      // bearer token client.ts's request interceptor reads out of safeStorage, and that storage
      // entry is what the Promise.all below deletes. Never throws (see pushRegistration.ts) and
      // never delays the rest of this IIFE by more than the one network round trip -- the whole
      // block already runs after clearLocalState() has signed the UI out.
      await revokeDeviceToken();

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
      value={{
        bootstrapping, token, email, fullName, phoneVerified,
        login, reactivate, register, loginWithGoogle, loginWithApple, setPhoneVerified, logout,
      }}
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
