import { useEffect } from 'react';
import { StyleSheet, View } from 'react-native';
import Animated, { useAnimatedStyle, useSharedValue, withRepeat, withTiming } from 'react-native-reanimated';
import { useTheme } from '../theme';

/**
 * Two large, very low-opacity circles drifting slowly behind the auth card -- the mobile
 * equivalent of the web auth page's `.float-slow`/`.float-slower` background shapes (see
 * frontend/src/pages/AuthEntry.tsx). Purely decorative (`pointerEvents="none"`), so it's mounted
 * as the first child of AuthScreenLayout's screen and absolutely fills it; every other child
 * renders after it in the same parent, which is enough for RN to stack them on top -- no z-index
 * needed.
 *
 * No new native dependency: a flat semi-transparent circle reads as a soft glow at this size and
 * opacity without needing an actual blur (expo-blur) or gradient (expo-linear-gradient) -- both
 * would need a native rebuild to even test, which isn't available in every dev environment this
 * redesign has been verified in (see the PR description's disclosed simulator-verification gap).
 *
 * `withTiming`'s own `reduceMotion` default (`ReduceMotion.System`) already halts this under the
 * OS's reduced-motion setting, same reasoning as Button's pressScale.
 */
function useDrift(distance: number, duration: number) {
  const value = useSharedValue(0);
  // In an effect, not assigned directly in the render body -- a direct assignment would restart
  // this infinite repeat from scratch on every re-render of AuthAmbientBackground (e.g. a theme
  // change), not just once on mount.
  useEffect(() => {
    value.value = withRepeat(withTiming(distance, { duration }), -1, true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  return value;
}

export function AuthAmbientBackground() {
  const c = useTheme();
  const driftA = useDrift(24, 9000);
  const driftB = useDrift(-20, 11000);

  const styleA = useAnimatedStyle(() => ({
    transform: [{ translateX: driftA.value }, { translateY: driftA.value * 0.6 }],
  }));
  const styleB = useAnimatedStyle(() => ({
    transform: [{ translateX: driftB.value }, { translateY: driftB.value * -0.5 }],
  }));

  return (
    <View style={StyleSheet.absoluteFill} pointerEvents="none">
      <Animated.View style={[styles.shape, styles.shapeA, { backgroundColor: c.primaryLight }, styleA]} />
      <Animated.View style={[styles.shape, styles.shapeB, { backgroundColor: c.primaryLight }, styleB]} />
    </View>
  );
}

const styles = StyleSheet.create({
  shape: {
    position: 'absolute',
    borderRadius: 999,
    opacity: 0.5,
  },
  shapeA: {
    width: 260,
    height: 260,
    top: -80,
    left: -60,
  },
  shapeB: {
    width: 220,
    height: 220,
    bottom: -60,
    right: -70,
  },
});
