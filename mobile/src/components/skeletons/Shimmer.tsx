import { useEffect } from 'react';
import {
  AccessibilityInfo, Animated, StyleSheet, type DimensionValue, type ViewStyle,
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
    // releaseSharedLoop's stop() below freezes sharedPulse wherever it was mid-fade, not
    // necessarily back at 0.35 -- without this reset, the first pulse of a new loading episode
    // starts from that leftover value instead of the intended minimum, reading as a stutter.
    sharedPulse.setValue(0.35);
    sharedLoop = Animated.loop(
      Animated.sequence([
        Animated.timing(sharedPulse, { toValue: 1, duration: 700, useNativeDriver: true }),
        Animated.timing(sharedPulse, { toValue: 0.35, duration: 700, useNativeDriver: true }),
      ])
    );
    sharedLoop.start();
    // Every individual Shimmer block is hidden from the accessibility tree below (a screen reader
    // reading out a dozen unlabelled "gray rectangle" placeholders is worse than reading nothing),
    // but that leaves NO signal at all that the screen is loading -- the old spinner this system
    // replaced was, at minimum, a real native element VoiceOver/TalkBack announced on its own. A
    // one-time announcement exactly when loading starts (this ref-count going 0->1 is that moment,
    // the same signal that already starts the shared pulse above) restores that without re-
    // exposing every placeholder individually or repeating the announcement per skeleton block.
    AccessibilityInfo.announceForAccessibility('Loading');
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
