import { useEffect, useState, type ReactNode } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { onlineManager } from '@tanstack/react-query';
import { SafeAreaInsetsContext, useSafeAreaInsets } from 'react-native-safe-area-context';
import { spacing, useTheme } from '../theme';

/** Subscribes to React Query's own notion of connectivity, fed by NetInfo in api/queryClient.ts. */
export function useOnline(): boolean {
  const [online, setOnline] = useState(() => onlineManager.isOnline());
  useEffect(() => onlineManager.subscribe(setOnline), []);
  return online;
}

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

  if (online) return <>{children}</>;

  return (
    <View style={styles.flex}>
      <View
        style={[styles.bar, { backgroundColor: c.warningBg, paddingTop: insets.top + 6 }]}
        accessibilityRole="alert"
        accessibilityLiveRegion="polite"
      >
        <Text style={[styles.text, { color: c.warningInk }]}>
          No connection — showing the last data loaded
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
