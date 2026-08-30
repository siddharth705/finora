import { useEffect } from 'react';
import {
  Animated, StyleSheet, type DimensionValue, type ViewStyle,
} from 'react-native';
import { radius, useTheme } from '../../theme';

export interface ShimmerProps {
  width?: DimensionValue;
  height: number;
  borderRadius?: number;
  style?: ViewStyle;
  testID?: string;
}

/**
 * One Animated.Value and one Animated.loop, shared by every Shimmer instance in the app, rather
 * than each instance running its own independent loop. A single loading shell can mount a couple
 * dozen Shimmer instances at once (e.g. Dashboard's first paint, or 8 skeleton rows x 3 Shimmers
 * each on Ledger) -- one shared driver means one JS->native-driver bridge call to start/stop
 * instead of one per instance, and every shimmer on screen pulses in sync as a side effect.
 * Ref-counted rather than always-on: the loop starts when the first Shimmer mounts and stops when
 * the last one unmounts, so it never runs in the background once nothing is loading anywhere.
 */
const sharedPulse = new Animated.Value(0.35);
let activeShimmerCount = 0;
let sharedLoop: Animated.CompositeAnimation | null = null;

function acquireSharedLoop() {
  activeShimmerCount += 1;
  if (activeShimmerCount === 1) {
    sharedLoop = Animated.loop(
      Animated.sequence([
        Animated.timing(sharedPulse, { toValue: 1, duration: 700, useNativeDriver: true }),
        Animated.timing(sharedPulse, { toValue: 0.35, duration: 700, useNativeDriver: true }),
      ])
    );
    sharedLoop.start();
  }
}

function releaseSharedLoop() {
  activeShimmerCount -= 1;
  if (activeShimmerCount === 0) {
    sharedLoop?.stop();
    sharedLoop = null;
  }
}

export function Shimmer({
  width = '100%', height, borderRadius = radius.md, style, testID = 'shimmer-block',
}: ShimmerProps) {
  const c = useTheme();

  useEffect(() => {
    acquireSharedLoop();
    return () => releaseSharedLoop();
  }, []);

  return (
    <Animated.View
      testID={testID}
      // Placeholder only -- it stands in for content a screen reader will hear announced once the
      // real value replaces it. Exposing it too would double-announce every field on first load.
      accessibilityElementsHidden
      importantForAccessibility="no-hide-descendants"
      style={[styles.block, { width, height, borderRadius, backgroundColor: c.border, opacity: sharedPulse }, style]}
    />
  );
}

const styles = StyleSheet.create({
  block: { overflow: 'hidden' },
});
