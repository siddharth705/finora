import { useEffect, useState } from 'react';
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
 * The shared pulse every skeleton component in this folder is built from -- one Animated.Value
 * looping between two opacities over a theme-aware block (`c.border`, already correct in both
 * light and dark -- see src/theme/palette.ts). There is no react-native-reanimated or gradient
 * library in this project (confirmed: neither appears in package.json), so this uses core
 * `Animated` from 'react-native' with useNativeDriver: true -- opacity is a native-driver-safe
 * property, so this needs no new dependency.
 */
export function Shimmer({
  width = '100%', height, borderRadius = radius.md, style, testID = 'shimmer-block',
}: ShimmerProps) {
  const c = useTheme();
  // useState's lazy initializer, not useRef(...).current -- this project's react-hooks/refs (React
  // Compiler) lint rule forbids reading .current during render at all, including the common
  // lazy-init idiom. useState's initializer runs exactly once and gives the same stable identity
  // across renders without ever touching a ref.
  const [pulse] = useState(() => new Animated.Value(0.35));

  useEffect(() => {
    const loop = Animated.loop(
      Animated.sequence([
        Animated.timing(pulse, { toValue: 1, duration: 700, useNativeDriver: true }),
        Animated.timing(pulse, { toValue: 0.35, duration: 700, useNativeDriver: true }),
      ])
    );
    loop.start();
    return () => loop.stop();
  }, [pulse]);

  return (
    <Animated.View
      testID={testID}
      // Placeholder only -- it stands in for content a screen reader will hear announced once the
      // real value replaces it. Exposing it too would double-announce every field on first load.
      accessibilityElementsHidden
      importantForAccessibility="no-hide-descendants"
      style={[styles.block, { width, height, borderRadius, backgroundColor: c.border, opacity: pulse }, style]}
    />
  );
}

const styles = StyleSheet.create({
  block: { overflow: 'hidden' },
});
