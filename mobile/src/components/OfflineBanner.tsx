import { useEffect, useRef, useState, type ReactNode } from 'react';
import { AccessibilityInfo, Platform, StyleSheet, Text, View } from 'react-native';
import { onlineManager } from '@tanstack/react-query';
import { SafeAreaInsetsContext, useSafeAreaInsets } from 'react-native-safe-area-context';
import { spacing, useTheme } from '../theme';
import { useTransientFlag } from '../lib/useTransientFlag';

/** Subscribes to React Query's own notion of connectivity, fed by NetInfo in api/queryClient.ts.
 *  Exported so anything that needs the identical online/offline signal the banner itself renders
 *  from (usePrefetchAdjacentScreens) reads from this one source rather than a second NetInfo
 *  subscription that could disagree with what's on screen. */
export function useOnline(): boolean {
  const [online, setOnline] = useState(() => onlineManager.isOnline());
  useEffect(() => onlineManager.subscribe(setOnline), []);
  return online;
}

/** Single source of truth for the offline message -- spoken by iOS below, and rendered by the
 *  banner's own <Text> further down, so the two can never drift apart. */
const OFFLINE_MESSAGE = 'No connection — showing the last data loaded';

/** Shown briefly on the SAME banner when connectivity returns -- see OfflineBoundary's own
 *  comment for why this reuses useTransientFlag rather than a new component or a modal. */
const BACK_ONLINE_MESSAGE = 'Back online — refreshing your data';
const BACK_ONLINE_DURATION_MS = 2500;

/**
 * Wraps the app with a persistent offline strip.
 *
 * Reads from React Query's onlineManager rather than subscribing to NetInfo separately, so the
 * banner and the query layer can never disagree -- if this is visible, queries really are paused,
 * and when it clears they really do resume.
 *
 * Deliberately not a toast: being offline is a state, not an event, and a message that vanishes
 * after three seconds leaves someone staring at stale figures with no explanation. A financial app
 * showing numbers that silently stopped updating is worse than one that admits it.
 *
 * The SafeAreaInsetsContext override is the non-obvious part. The banner sits above the navigator
 * and consumes the top inset to clear the notch -- but every screen also pads by `insets.top` of
 * its own, so without this the notch allowance would be counted twice and everything would jump
 * down by ~47pt the moment connectivity dropped. Re-providing `top: 0` to the subtree tells those
 * screens the top inset is already spent. Everything reverts automatically when the banner goes.
 */
export function OfflineBoundary({ children }: { children: ReactNode }) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const online = useOnline();
  const [showingBackOnline, confirmBackOnline] = useTransientFlag(BACK_ONLINE_DURATION_MS);

  // accessibilityLiveRegion below is Android-only -- React Native has no iOS equivalent, so a
  // VoiceOver user gets no signal that the banner just appeared unless something explicitly
  // speaks it. Tracked by ref rather than a second piece of render state: this only ever needs to
  // fire a side effect, never to affect what's drawn, and skipping the initial mount keeps it
  // symmetric with Android's live region (which also stays silent for content present at mount --
  // both only announce a live *change*, not the app opening already offline).
  const wasOnline = useRef(online);
  useEffect(() => {
    if (Platform.OS === 'ios' && wasOnline.current && !online) {
      AccessibilityInfo.announceForAccessibility(OFFLINE_MESSAGE);
    }
    // The reverse transition: connectivity just came back. Triggers the same transient-flag
    // pattern BudgetsScreen's "Saved." confirmation uses (see useTransientFlag's own doc comment
    // on why a hand-rolled setTimeout here would leak) rather than a toast or modal -- staying
    // consistent with this component's "being offline is a state, not an event" reasoning: coming
    // back online IS an event, briefly, and this is the one place in the boundary allowed to be.
    if (!wasOnline.current && online) {
      confirmBackOnline();
      if (Platform.OS === 'ios') {
        AccessibilityInfo.announceForAccessibility(BACK_ONLINE_MESSAGE);
      }
    }
    wasOnline.current = online;
  }, [online, confirmBackOnline]);

  if (online && !showingBackOnline) return <>{children}</>;

  const barColor = online ? c.successBg : c.warningBg;
  const textColor = online ? c.successInk : c.warningInk;
  const message = online ? BACK_ONLINE_MESSAGE : OFFLINE_MESSAGE;

  return (
    <View style={styles.flex}>
      <View
        style={[styles.bar, { backgroundColor: barColor, paddingTop: insets.top + 6 }]}
        // `accessible` groups the strip into one announced element rather than leaving the role on
        // a container a screen reader steps past on its way to the text. It is also what makes the
        // role queryable, so the announcement can actually be asserted rather than assumed.
        accessible
        accessibilityRole="alert"
        accessibilityLiveRegion="polite"
      >
        <Text style={[styles.text, { color: textColor }]}>{message}</Text>
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
