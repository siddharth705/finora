import { ActivityIndicator, Pressable, StyleSheet, Text } from 'react-native';
import Animated, { useAnimatedStyle, useSharedValue, withTiming } from 'react-native-reanimated';
import { radius, useTheme } from '../theme';

interface Props {
  label: string;
  onPress: () => void;
  loading?: boolean;
  disabled?: boolean;
  variant?: 'primary' | 'link';
  /** For Maestro, not React Native's own accessibility tree -- several screens have a heading and
   *  a button with the identical label (e.g. AuthScreenLayout's title="Sign in" above LoginScreen's
   *  own "Sign in" submit button), which a text-only Maestro selector can't tell apart from a
   *  non-interactive one. Optional and unused by anything else; see mobile/.maestro/README.md. */
  testID?: string;
  /**
   * Opt-in press-scale feedback (auth redesign Phase 3) -- deliberately NOT the default for every
   * Button in the app. This component is shared app-wide; changing its default feel would be an
   * app-wide interaction change nobody asked for, not an auth-screen one. `withTiming`'s own
   * `reduceMotion` default (`ReduceMotion.System`) already skips this under the OS's reduced-motion
   * setting, so no separate check is needed here.
   */
  pressScale?: boolean;
}

export function Button({
  label, onPress, loading = false, disabled = false, variant = 'primary', testID, pressScale = false,
}: Props) {
  const c = useTheme();
  const isDisabled = disabled || loading;
  const scale = useSharedValue(1);
  const scaleStyle = useAnimatedStyle(() => ({ transform: [{ scale: scale.value }] }));

  // Mutating `.value` directly is Reanimated's own documented API for a SharedValue -- it's
  // deliberately outside React's state model (that's the whole point: it updates the UI thread
  // without a re-render), which is exactly what react-hooks/immutability's static analysis can't
  // yet tell apart from mutating a plain hook return value.
  function handlePressIn() {
    // eslint-disable-next-line react-hooks/immutability
    scale.value = withTiming(0.97, { duration: 100 });
  }

  function handlePressOut() {
    // eslint-disable-next-line react-hooks/immutability
    scale.value = withTiming(1, { duration: 150 });
  }

  if (variant === 'link') {
    return (
      <Pressable onPress={onPress} disabled={isDisabled} hitSlop={8} accessibilityRole="button" testID={testID}>
        <Text style={[styles.linkLabel, { color: c.primary }, isDisabled && styles.disabled]}>{label}</Text>
      </Pressable>
    );
  }

  const pressable = (
    <Pressable
      onPress={onPress}
      onPressIn={pressScale ? handlePressIn : undefined}
      onPressOut={pressScale ? handlePressOut : undefined}
      disabled={isDisabled}
      accessibilityRole="button"
      accessibilityState={{ disabled: isDisabled, busy: loading }}
      testID={testID}
      style={({ pressed }) => [
        styles.button,
        { backgroundColor: pressed ? c.primaryDark : c.primary },
        isDisabled && styles.disabled,
      ]}
    >
      {loading ? (
        <ActivityIndicator color={c.onPrimary} size="small" />
      ) : (
        <Text style={[styles.label, { color: c.onPrimary }]}>{label}</Text>
      )}
    </Pressable>
  );

  // Unwrapped when pressScale is off, so every existing call site keeps its exact current tree --
  // no extra View, no behavior change.
  return pressScale ? <Animated.View style={scaleStyle}>{pressable}</Animated.View> : pressable;
}

const styles = StyleSheet.create({
  button: {
    borderRadius: radius.md,
    paddingVertical: 14,
    alignItems: 'center',
    justifyContent: 'center',
    minHeight: 48,
  },
  label: {
    fontSize: 15,
    fontWeight: '600',
  },
  linkLabel: {
    fontSize: 13,
    fontWeight: '500',
    textAlign: 'center',
  },
  disabled: {
    opacity: 0.5,
  },
});
