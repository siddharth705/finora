import { useEffect, useRef, useState, type ReactNode } from 'react';
import { AccessibilityInfo, Platform, StyleSheet, Text, View } from 'react-native';
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

/** Single source of truth for the warning message -- spoken by iOS below, and rendered by the
 *  banner's own <Text> further down, so the two can never drift apart. */
const ROOT_WARNING_MESSAGE =
  "This device appears to be rooted or jailbroken — Fynora's own protections may not be fully effective here";

/** Nest inside OfflineBoundary (or vice versa) freely -- both follow the identical "consume the
 *  real top inset once, then zero it for children" pattern, so stacking either order composes
 *  correctly with no double notch spacing. See App.tsx for the actual ordering used. */
export function RootWarningBoundary({ children }: { children: ReactNode }) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const rooted = useIsRooted();

  // accessibilityLiveRegion below is Android-only -- React Native has no iOS equivalent, so a
  // VoiceOver user gets no signal that this banner just appeared unless something explicitly
  // speaks it. Same fix, same reasoning, as OfflineBanner's identical gap: announce only on the
  // false -> true transition (never on mount already-rooted), which in practice fires at most
  // once per session since root status doesn't change mid-session -- but tracking it as a
  // transition rather than "just announce whenever rooted is true" keeps this component's
  // behavior identical in shape to OfflineBanner's, which this already mirrors structurally.
  const wasRooted = useRef(rooted);
  useEffect(() => {
    if (Platform.OS === 'ios' && !wasRooted.current && rooted) {
      AccessibilityInfo.announceForAccessibility(ROOT_WARNING_MESSAGE);
    }
    wasRooted.current = rooted;
  }, [rooted]);

  if (!rooted) return <>{children}</>;

  return (
    <View style={styles.flex}>
      <View
        style={[styles.bar, { backgroundColor: c.warningBg, paddingTop: insets.top + 6 }]}
        accessible
        accessibilityRole="alert"
        accessibilityLiveRegion="polite"
      >
        <Text style={[styles.text, { color: c.warningInk }]}>{ROOT_WARNING_MESSAGE}</Text>
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
