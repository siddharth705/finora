import { useEffect, useState } from 'react';
import { ActivityIndicator, Switch, Text, View, StyleSheet } from 'react-native';
import * as appLock from '../../lib/appLock';
import { spacing, useTheme } from '../../theme';

/**
 * SEC-09 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). Self-contained,
 * mirroring DeviceSessionsSection's own shape -- its own data (device support + current setting)
 * and its own loading/error handling, dropped into SettingsScreen's existing Security section.
 *
 * Turning ON requires a real successful authentication before the setting actually takes effect
 * -- see handleToggle. A bare switch flip would let someone enable app-lock and never discover
 * their fingerprint isn't enrolled, or that the device has no biometric hardware at all, until the
 * next time they reopen the app and are stuck looking at a lock screen that was never going to
 * succeed (the device passcode fallback in appLock.authenticate() covers "no biometrics enrolled"
 * on most devices, but not "no secure hardware/passcode set up at all" -- confirming here, once,
 * while the user is already looking at Settings, is cheaper than debugging that report later).
 */
export function AppLockSection() {
  const c = useTheme();
  const [loading, setLoading] = useState(true);
  const [supported, setSupported] = useState(false);
  const [enabled, setEnabledState] = useState(false);
  // Bug found in review (Track D/D1/D6): distinct from `supported` -- this tracks whether the
  // CURRENT on/off state could actually be read, not whether the hardware exists at all. A
  // transient SecureStore failure leaves this false, and the switch is not shown until it clears,
  // rather than guessing an ON or OFF state the user never actually chose.
  const [confirmed, setConfirmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void Promise.all([appLock.isSupported(), appLock.isEnabledConfirmed()]).then(([supp, { enabled: en, confirmed: conf }]) => {
      if (cancelled) return;
      setSupported(supp);
      setEnabledState(en);
      setConfirmed(conf);
      setLoading(false);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  async function handleToggle(next: boolean) {
    setError(null);
    if (!next) {
      // Turning off never needs proof of anything -- the account's own session (already
      // authenticated) is what's authorizing this change, same as every other Settings toggle.
      setBusy(true);
      await appLock.setEnabled(false);
      setEnabledState(false);
      setConfirmed(true);
      setBusy(false);
      return;
    }
    setBusy(true);
    try {
      const didAuthenticate = await appLock.authenticate('Confirm to enable App Lock');
      if (didAuthenticate) {
        await appLock.setEnabled(true);
        setEnabledState(true);
        setConfirmed(true);
      } else {
        setError('Could not confirm — App Lock was not enabled.');
      }
    } finally {
      setBusy(false);
    }
  }

  if (loading) {
    return (
      <View style={[styles.row, { borderBottomColor: c.border }]}>
        <ActivityIndicator color={c.primary} />
      </View>
    );
  }

  if (!supported) {
    return (
      <View style={[styles.row, { borderBottomColor: c.border }]}>
        <View style={styles.rowMain}>
          <Text style={[styles.rowTitle, { color: c.ink }]}>App Lock</Text>
          <Text style={[styles.rowMeta, { color: c.mutedInk }]}>
            Set up a fingerprint, face, or passcode on this device to use App Lock.
          </Text>
        </View>
      </View>
    );
  }

  if (!confirmed) {
    return (
      <View style={[styles.row, { borderBottomColor: c.border }]}>
        <View style={styles.rowMain}>
          <Text style={[styles.rowTitle, { color: c.ink }]}>App Lock</Text>
          <Text style={[styles.rowMeta, { color: c.danger }]}>
            Couldn&apos;t check whether App Lock is on. Try reopening Settings.
          </Text>
        </View>
      </View>
    );
  }

  return (
    <View>
      <View style={[styles.row, { borderBottomColor: c.border }]}>
        <View style={styles.rowMain}>
          <Text style={[styles.rowTitle, { color: c.ink }]}>App Lock</Text>
          <Text style={[styles.rowMeta, { color: c.mutedInk }]}>
            Require your fingerprint, face, or device passcode to open Fynora.
          </Text>
        </View>
        <Switch
          value={enabled}
          onValueChange={(next) => void handleToggle(next)}
          disabled={busy}
          trackColor={{ true: c.primary, false: c.border }}
          // The native thumb defaults to a plain white circle regardless of track color. That
          // was fine against a mid-to-dark blue "on" track in both themes, but dark mode's track
          // is now light paper (c.primary), where a white thumb nearly disappears into it --
          // explicit onPrimary keeps the thumb visible against whichever track color is active.
          thumbColor={enabled ? c.onPrimary : undefined}
          accessibilityLabel="App Lock"
          accessibilityHint="Requires biometric or device passcode authentication to open the app"
        />
      </View>
      {error ? <Text style={[styles.error, { color: c.danger }]}>{error}</Text> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 10,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  rowMain: { flex: 1, marginRight: spacing.sm },
  rowTitle: { fontSize: 14, fontWeight: '600' },
  rowMeta: { fontSize: 12, marginTop: 2 },
  error: { fontSize: 12, marginTop: spacing.xs, marginBottom: spacing.sm },
});
