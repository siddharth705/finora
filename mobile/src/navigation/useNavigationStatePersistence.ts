import { useEffect, useRef, useState } from 'react';
import AsyncStorage from '@react-native-async-storage/async-storage';
import type { NavigationState, PartialState } from '@react-navigation/native';

const NAV_STATE_KEY = 'finora_nav_state';

type PersistableState = NavigationState | PartialState<NavigationState>;

/**
 * Strips `params` from every route in the tree before it's written to disk, recursively.
 *
 * Deliberately an allowlist of what DOES survive (route names and nesting, nothing else) rather
 * than a denylist of the routes known to be sensitive today. Two routes already carry values that
 * must never sit in plaintext AsyncStorage: VerifyEmailChange's `sessionId`/`token` (a one-time
 * email-verification link, see types.ts) and Import's `reimport.password` (a protected
 * statement's password, carried in memory only by design -- see AppTabParamList's own comment on
 * why it isn't dropped anywhere upstream of this). A denylist only catches the ones someone
 * remembered to add to it; stripping every param unconditionally means a future screen that adds
 * a new sensitive param is safe by default, the same way clearLocalState's full-cache clear in
 * AuthContext is safe by default rather than an allowlist of keys to remove.
 */
function stripParams(state: PersistableState): PersistableState {
  return {
    ...state,
    routes: state.routes.map((route) => {
      const { params: _params, ...rest } = route;
      return {
        ...rest,
        ...(route.state ? { state: stripParams(route.state) } : {}),
      };
    }),
  } as PersistableState;
}

/**
 * Restores the user to the same tab/screen after a process-death restart -- important for a long
 * in-progress workflow (import review, a half-filled form) that a background kill would otherwise
 * throw away silently. See RootNavigator's own comment on why this is scoped to `active` only:
 * state is captured and restored ONLY while the signed-in AppTabs tree is what's mounted, never
 * the auth stack or the single-screen phone-verification stack -- those state shapes don't match
 * AppTabs's, and there's no value in restoring a signed-out user back into mid-sign-in UI anyway.
 *
 * `bootstrapping` gates the read: `active` isn't trustworthy until AuthContext has finished
 * restoring the session from SecureStore (see AuthContext's own doc comment on why token starts
 * null), so loading persisted nav state before then would read whatever `active` was on the very
 * first render -- before the real session is known -- and never revisit it, since the effect only
 * runs once.
 */
export function useNavigationStatePersistence(bootstrapping: boolean, active: boolean) {
  const [isReady, setIsReady] = useState(false);
  const [initialState, setInitialState] = useState<NavigationState | undefined>(undefined);
  // Ref, not a plain read of `active`, because onStateChange below is a stable function identity
  // read by NavigationContainer at mount and needs the CURRENT active value whenever it fires --
  // not whatever it closed over on the render that created it. Written in an effect (not during
  // render) per react-hooks/refs: mutating a ref's .current outside an effect or event handler is
  // exactly what that rule exists to catch.
  const activeRef = useRef(active);
  useEffect(() => {
    activeRef.current = active;
  });

  useEffect(() => {
    if (bootstrapping) return;
    let cancelled = false;
    (async () => {
      try {
        if (activeRef.current) {
          const raw = await AsyncStorage.getItem(NAV_STATE_KEY);
          const saved = raw ? (JSON.parse(raw) as NavigationState) : undefined;
          if (!cancelled && saved) setInitialState(saved);
        }
      } catch {
        // Corrupt or unreadable persisted state is no worse than none -- fall through to
        // whatever AppTabs's own default initial route is, same as a cold install would get.
      } finally {
        if (!cancelled) setIsReady(true);
      }
    })();
    return () => {
      cancelled = true;
    };
    // Only `activeRef.current` is read inside, not `active` itself, so exhaustive-deps has
    // nothing to flag here -- this is meant to run once per bootstrapping flip, not on every
    // `active` change.
  }, [bootstrapping]);

  function onStateChange(state: NavigationState | undefined) {
    if (!activeRef.current || !state) return;
    void AsyncStorage.setItem(NAV_STATE_KEY, JSON.stringify(stripParams(state)));
  }

  return { isReady, initialState, onStateChange };
}

/**
 * Called from AuthContext's clearLocalState -- the same convergence point that clears the React
 * Query cache on every sign-out/expiry (see its own doc comment on why a leaked-across-users
 * balance bug made that a single required point rather than a list of callers to remember). A
 * persisted screen position is a smaller leak than a balance, but the next person signing in on
 * this device landing on whatever screen the previous account was last viewing is still a mistake
 * worth ruling out the same way.
 */
export async function clearPersistedNavigationState(): Promise<void> {
  await AsyncStorage.removeItem(NAV_STATE_KEY);
}
