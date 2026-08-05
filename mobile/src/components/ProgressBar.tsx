import { StyleSheet, View } from 'react-native';
import { useTheme } from '../theme';

/**
 * The web app draws this inline in Budgets.tsx, Goals.tsx, Reports.tsx and Dashboard.tsx as a
 * `h-2 bg-black/10` track with a colored fill. Four copies there; one component here.
 *
 * Deliberately renders no text and carries no accessibility role of its own: on every screen that
 * uses it, the same numbers are already in a visible label beside it (e.g. "₹4,200 / ₹6,000"), so
 * announcing the bar as well would read the same fact twice.
 */
export function ProgressBar({ pct, color }: { pct: number; color: string }) {
  const c = useTheme();
  // Clamped rather than trusted: a spend that overshoots its limit gives pct > 100, which would
  // otherwise draw a fill wider than its own track.
  //
  // NaN is checked separately because it survives clamping -- Math.min/max propagate it rather
  // than choosing a bound -- and `width: "NaN%"` is not a value React Native's layout can use. It
  // arrives whenever a caller divides by a limit the API sent as null, which is exactly the case a
  // shared component should absorb rather than each caller remembering to guard.
  const safePct = Number.isFinite(pct) ? Math.max(0, Math.min(100, pct)) : 0;
  const width = `${safePct}%` as const;
  return (
    <View
      style={[styles.track, { backgroundColor: c.border }]}
      accessibilityElementsHidden
      importantForAccessibility="no-hide-descendants"
    >
      <View style={[styles.fill, { width, backgroundColor: color }]} />
    </View>
  );
}

const styles = StyleSheet.create({
  track: { height: 8, borderRadius: 4, overflow: 'hidden' },
  fill: { height: 8, borderRadius: 4 },
});
