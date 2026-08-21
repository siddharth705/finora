import { useEffect, useState, type ReactNode } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import * as Device from 'expo-device';
import { SafeAreaInsetsContext, useSafeAreaInsets } from 'react-native-safe-area-context';
import { spacing, useTheme } from '../theme';

/**
 * SEC-08 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). A compromised OS
 * undermines the Keychain/Keystore threat model expo-secure-store otherwise relies on for every
 * credential this app holds -- worth surfacing, deliberately as a warning rather than a block.
 *
 * <h2>Why a banner, not a hard block</h2>
 *
 * {@link Device.isRootedExperimentalAsync}'s own docs call it experimental and "not completely
 * reliable" -- Android can false-positive on a device that merely has a `su`-named executable
 * without being rooted, and dedicated jailbreak-hiding tools exist for iOS specifically to defeat
 * checks like this one. A control that can both false-positive AND be silently bypassed has no
 * business making an unconditional access decision; refusing to let a falsely-flagged legitimate
 * user (or a developer's own test device) into their own financial data is a worse failure mode
 * than the exposure this closes for the cases it correctly catches.
 *
 * Same "a state, not an event" reasoning as OfflineBanner (which this mirrors structurally) --
 * root status doesn't change mid-session, so this checks once rather than polling, and stays
 * visible for as long as the condition holds rather than being a dismissible one-shot toast.
 */
function useIsRooted(): boolean {
  const [rooted, setRooted] = useState(false);
  useEffect(() => {
    let cancelled = false;
    void Device.isRootedExperimentalAsync()
      .then((result) => {
        if (!cancelled) setRooted(result);
      })
      .catch(() => {
        // Fails closed to "not flagged" -- a detection check that itself errored has told us
        // nothing, and showing a security warning on the strength of a caught exception would be
        // exactly the false-confidence problem this component's own doc comment warns against.
      });
    return () => {
      cancelled = true;
    };
  }, []);
  return rooted;
}

/** Nest inside OfflineBoundary (or vice versa) freely -- both follow the identical "consume the
 *  real top inset once, then zero it for children" pattern, so stacking either order composes
 *  correctly with no double notch spacing. See App.tsx for the actual ordering used. */
export function RootWarningBoundary({ children }: { children: ReactNode }) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const rooted = useIsRooted();

  if (!rooted) return <>{children}</>;

  return (
    <View style={styles.flex}>
      <View
        style={[styles.bar, { backgroundColor: c.warningBg, paddingTop: insets.top + 6 }]}
        accessible
        accessibilityRole="alert"
        accessibilityLiveRegion="polite"
      >
        <Text style={[styles.text, { color: c.warningInk }]}>
          This device appears to be rooted or jailbroken — Finora's own protections may not be fully effective here
        </Text>
      </View>
      <SafeAreaInsetsContext.Provider value={{ ...insets, top: 0 }}>
        <View style={styles.flex}>{children}</View>
      </SafeAreaInsetsContext.Provider>
    </View>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  bar: {
    paddingHorizontal: spacing.md,
    paddingBottom: 6,
  },
  text: {
    fontSize: 12,
    fontWeight: '600',
    textAlign: 'center',
  },
});
