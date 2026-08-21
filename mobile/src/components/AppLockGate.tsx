import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { AppState, type AppStateStatus, StyleSheet, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { Button } from './Button';
import { useAuth } from '../context/AuthContext';
import * as appLock from '../lib/appLock';
import { spacing, useTheme } from '../theme';

/**
 * SEC-09 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). Re-locks on two
 * events: the first render of an authenticated session (cold start, or right after logging in
 * with the setting already on from a previous session), and every foreground transition
 * (AppState background/inactive -> active) -- the second is the case that actually matters day to
 * day: someone puts the phone down mid-session, or switches away to another app, and picking it
 * back up should not hand over the dashboard without a check.
 *
 * Deliberately does nothing when there is no session (token === null) -- locking the LOGIN screen
 * behind a biometric prompt protects nothing (there's no data behind it yet) and would just be a
 * confusing extra step before someone can even sign in.
 *
 * Reads appLock.isEnabled() fresh at both check points rather than caching it in state -- the
 * setting is toggled from a different component (AppLockSection, in Settings) with no shared
 * state between them, so a cached value would go stale the moment someone flips it mid-session.
 */
// Sentinel initial value for `checkedForToken` below -- distinct from any real token AND from
// `null` (the signed-out value), so the very first render never accidentally matches `token`.
const NEVER_CHECKED = Symbol('app-lock-never-checked');

export function AppLockGate({ children }: { children: ReactNode }) {
  const { bootstrapping, token, logout } = useAuth();
  const c = useTheme();
  const [locked, setLocked] = useState(false);
  const [authenticating, setAuthenticating] = useState(false);
  const appState = useRef(AppState.currentState);
  // Which token the last completed appLock.isEnabled() check applies to -- compared against the
  // current `token` below (`checked`) rather than a separate true/false flag, so there's no
  // "reset to not-checked" to perform synchronously when a session ends: a stale value here simply
  // stops matching `token` the moment a new one is set. Matters for a logout-then-different-login
  // within the same app session, since this component never unmounts across that transition and a
  // leftover match from the PRIOR session would skip the gate below exactly like the cold-start
  // race it exists to close.
  const [checkedToken, setCheckedToken] = useState<string | null | typeof NEVER_CHECKED>(
    NEVER_CHECKED
  );
  const checked = checkedToken === token;

  const tryUnlock = useCallback(async () => {
    setAuthenticating(true);
    try {
      const success = await appLock.authenticate('Unlock Finora');
      if (success) setLocked(false);
    } finally {
      setAuthenticating(false);
    }
  }, []);

  // Locks AND immediately prompts as one action, rather than setting `locked` and letting a
  // separate effect react to it -- the auto-prompt-on-lock behavior is intrinsic to what "locking"
  // means here (the button on the screen below exists for the RETRY after a cancelled/failed
  // attempt, not as the only way to trigger the first one), and folding it into one function
  // avoids a setState-cascading-through-an-effect chain for behavior that isn't actually reacting
  // to external state -- it's this component's own next step.
  const lockAndPrompt = useCallback(() => {
    setLocked(true);
    void tryUnlock();
  }, [tryUnlock]);

  // Reads the per-device setting once auth has finished bootstrapping and there's a session to
  // protect -- see appLock.ts's own comment on why this lives outside the account's server-side
  // settings and has to be readable before anything is unlocked. Records which token this check
  // resolved for regardless of the outcome, so the render gate above knows the decision is in and
  // it's safe to show `children` when the setting turns out to be off.
  useEffect(() => {
    if (bootstrapping || token === null) return;
    let cancelled = false;
    void appLock.isEnabled().then((enabled) => {
      if (cancelled) return;
      setCheckedToken(token);
      // Explicit either way, not just "if (enabled) lockAndPrompt()" -- `locked` is state on this
      // long-lived component, not per-session, so without an explicit false here a PRIOR session
      // that ended while still locked (e.g. logging out from the lock screen's own Sign Out
      // button) would leave a new sign-in that never enabled the setting stuck looking locked.
      if (enabled) {
        lockAndPrompt();
      } else {
        setLocked(false);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [bootstrapping, token, lockAndPrompt]);

  // Reads appLock.isEnabled() fresh on every foreground transition rather than trusting a cached
  // value from the mount effect above -- AppLockSection (Settings) writes the setting directly to
  // SecureStore with no shared state or event bridge back to this component, so a value captured
  // once at cold start would go stale the moment the user flips the toggle in the same session:
  // turning it ON wouldn't engage until the app was fully killed and relaunched, and turning it
  // OFF wouldn't stop the next foreground prompt from firing anyway.
  useEffect(() => {
    const subscription = AppState.addEventListener('change', (next: AppStateStatus) => {
      // AppState.currentState (this ref's initial value) is genuinely nullable per its own
      // type -- unset until the native module responds -- so a stricter TS lib can correctly
      // flag a bare `.match()` here even though `next` below is never null.
      const cameToForeground = !!appState.current?.match(/inactive|background/) && next === 'active';
      appState.current = next;
      if (cameToForeground && token !== null) {
        void appLock.isEnabled().then((enabled) => {
          if (enabled) lockAndPrompt();
        });
      }
    });
    return () => subscription.remove();
  }, [token, lockAndPrompt]);

  if (bootstrapping || token === null) {
    return <>{children}</>;
  }
  // Session present but the check for THIS token hasn't resolved yet -- render nothing rather
  // than `children`, closing the cold-start race where RootNavigator would otherwise be paintable
  // for one commit before a lock-enabled session gets locked (and the equivalent race on a fresh
  // login right after a different session's logout, within the same app process).
  if (!checked) {
    return null;
  }
  if (!locked) {
    return <>{children}</>;
  }

  return (
    <View style={[styles.container, { backgroundColor: c.bg }]}>
      <Ionicons name="lock-closed" size={48} color={c.primary} />
      <Text style={[styles.title, { color: c.ink }]}>Finora is locked</Text>
      <Text style={[styles.subtitle, { color: c.muted }]}>
        Unlock with your fingerprint, face, or device passcode to continue.
      </Text>
      <View style={styles.actions}>
        <Button
          label={authenticating ? 'Unlocking…' : 'Unlock'}
          onPress={() => void tryUnlock()}
          loading={authenticating}
        />
        {/* Escape hatch, deliberately: if biometrics and the device passcode are ever both
            unusable (a broken sensor, a forgotten passcode), signing out is still self-service --
            there is no locked-out-of-the-app-with-no-way-back state this gate can create. */}
        <Button label="Sign Out" onPress={logout} variant="link" />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: spacing.xl,
    gap: spacing.sm,
  },
  title: { fontSize: 20, fontWeight: '700', marginTop: spacing.md },
  subtitle: { fontSize: 14, textAlign: 'center', marginBottom: spacing.lg },
  actions: { width: '100%', gap: spacing.sm, marginTop: spacing.md },
});
