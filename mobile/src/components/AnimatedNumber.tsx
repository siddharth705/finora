import { useEffect } from 'react';
import { StyleSheet, TextInput, type StyleProp, type TextStyle } from 'react-native';
import Animated, { Easing, useAnimatedProps, useSharedValue, withTiming } from 'react-native-reanimated';
import { fmtCurrency } from '../lib/format';
import { CHART_REVEAL_DURATION } from './charts/ChartReveal';

const AnimatedTextInput = Animated.createAnimatedComponent(TextInput);

/**
 * Indian digit grouping (last 3 digits, then pairs: "12,34,567") without `toLocaleString`/`Intl`.
 * Mirrors `fmtCurrency`'s output exactly for the non-negative integers this ever receives.
 *
 * Runs inside a UI-thread worklet, which is a separate, stripped-down JS runtime from the main
 * thread's Hermes (see format.ts's note on Hermes/ICU -- that note is about the main thread only).
 * Reanimated's worklet runtime is not guaranteed to carry full `Intl` support, so this avoids it
 * rather than assume parity that can't be verified without a real device build.
 */
function groupIndianDigits(digits: string): string {
  'worklet';
  if (digits.length <= 3) return digits;
  const last3 = digits.slice(-3);
  let rest = digits.slice(0, -3);
  let grouped = '';
  while (rest.length > 2) {
    grouped = ',' + rest.slice(-2) + grouped;
    rest = rest.slice(0, -2);
  }
  return rest + grouped + ',' + last3;
}

/** Worklet-safe equivalent of `fmtCurrency` -- see groupIndianDigits for why this can't just call it. */
function fmtCurrencyWorklet(n: number): string {
  'worklet';
  const digits = String(Math.round(Math.abs(n)));
  return (n < 0 ? '-₹' : '₹') + groupIndianDigits(digits);
}

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
export function AnimatedNumber({ value, style, duration = CHART_REVEAL_DURATION, testID }: AnimatedNumberProps) {
  const animated = useSharedValue(value);

  useEffect(() => {
    animated.value = withTiming(value, { duration, easing: Easing.out(Easing.cubic) });
  }, [value, duration, animated]);

  const animatedProps = useAnimatedProps(() => {
    // `text`/`defaultValue` aren't part of RN's public TextInputProps type, but both are real
    // native props TextInput accepts -- the cast mirrors Reanimated's own documented example.
    const formatted = fmtCurrencyWorklet(animated.value);
    return {
      text: formatted,
      defaultValue: formatted,
    } as Partial<React.ComponentProps<typeof TextInput>>;
  });

  return (
    <AnimatedTextInput
      testID={testID}
      editable={false}
      // `editable={false}` alone doesn't stop Android from treating the underlying EditText as its
      // own focusable node -- TalkBack would then announce this component's accessibilityLabel AND
      // separately land focus on the raw text content, a double-announcement most visible next to
      // sibling text (BudgetsScreen's amount row). `focusable={false}` keeps it out of the focus
      // order entirely; accessibilityLabel below is what a wrapping `accessible` parent reads.
      focusable={false}
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
