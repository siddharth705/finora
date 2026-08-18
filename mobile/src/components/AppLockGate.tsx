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
 */
export function AppLockGate({ children }: { children: ReactNode }) {
  const { bootstrapping, token, logout } = useAuth();
  const c = useTheme();
  const [lockEnabled, setLockEnabled] = useState(false);
  const [locked, setLocked] = useState(false);
  const [authenticating, setAuthenticating] = useState(false);
  const appState = useRef(AppState.currentState);

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
  // settings and has to be readable before anything is unlocked.
  useEffect(() => {
    if (bootstrapping || token === null) return;
    let cancelled = false;
    void appLock.isEnabled().then((enabled) => {
      if (cancelled) return;
      setLockEnabled(enabled);
      if (enabled) lockAndPrompt();
    });
    return () => {
      cancelled = true;
    };
  }, [bootstrapping, token, lockAndPrompt]);

  useEffect(() => {
    const subscription = AppState.addEventListener('change', (next: AppStateStatus) => {
      const cameToForeground = appState.current.match(/inactive|background/) && next === 'active';
      appState.current = next;
      if (cameToForeground && lockEnabled && token !== null) {
        lockAndPrompt();
      }
    });
    return () => subscription.remove();
  }, [lockEnabled, token, lockAndPrompt]);

  if (bootstrapping || token === null || !locked) {
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
