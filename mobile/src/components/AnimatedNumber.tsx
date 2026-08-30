import { useEffect } from 'react';
import { StyleSheet, TextInput, type StyleProp, type TextStyle } from 'react-native';
import Animated, { Easing, useAnimatedProps, useSharedValue, withTiming } from 'react-native-reanimated';
import { fmtCurrency } from '../lib/format';

const AnimatedTextInput = Animated.createAnimatedComponent(TextInput);

interface AnimatedNumberProps {
  value: number;
  style?: StyleProp<TextStyle>;
  /** Milliseconds for the transition. Short and eased, not sprung -- the brief's "no flashy
   * casino-style animation" rule rules out overshoot, so this is a plain ease-out, never a
   * bounce. */
  duration?: number;
  testID?: string;
}

/**
 * Smoothly transitions the displayed value when `value` changes, instead of a hard jump cut --
 * balances/totals ticking rather than flashing to a new number.
 *
 * On a non-editable TextInput rather than a <Text>: Reanimated's useAnimatedProps can only update
 * a native prop directly on the UI thread without a JS re-render, and TextInput's `text` prop is
 * the one built-in RN component prop it can drive that way (the same technique used by
 * Reanimated's own AnimatedCounter cookbook example). Content is otherwise a plain label: not
 * focusable, not editable, no cursor, no keyboard.
 *
 * No animation on first mount: the shared value initialises to the same value it's animating
 * towards, so the very first render shows the correct number immediately -- this only animates a
 * value that CHANGES after mount (a refresh, a save), never a count-up intro.
 */
export function AnimatedNumber({ value, style, duration = 400, testID }: AnimatedNumberProps) {
  const animated = useSharedValue(value);

  useEffect(() => {
    animated.value = withTiming(value, { duration, easing: Easing.out(Easing.cubic) });
  }, [value, duration, animated]);

  const animatedProps = useAnimatedProps(() => {
    // `text`/`defaultValue` aren't part of RN's public TextInputProps type, but both are real
    // native props TextInput accepts -- the cast mirrors Reanimated's own documented example.
    return {
      text: fmtCurrency(animated.value),
      defaultValue: fmtCurrency(animated.value),
    } as Partial<React.ComponentProps<typeof TextInput>>;
  });

  return (
    <AnimatedTextInput
      testID={testID}
      editable={false}
      pointerEvents="none"
      underlineColorAndroid="transparent"
      style={[styles.text, style]}
      animatedProps={animatedProps}
      accessibilityLabel={fmtCurrency(value)}
    />
  );
}

const styles = StyleSheet.create({
  text: { padding: 0, margin: 0 },
});
