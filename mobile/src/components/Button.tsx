import { ActivityIndicator, Pressable, StyleSheet, Text } from 'react-native';
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
}

export function Button({ label, onPress, loading = false, disabled = false, variant = 'primary', testID }: Props) {
  const c = useTheme();
  const isDisabled = disabled || loading;

  if (variant === 'link') {
    return (
      <Pressable onPress={onPress} disabled={isDisabled} hitSlop={8} accessibilityRole="button" testID={testID}>
        <Text style={[styles.linkLabel, { color: c.primary }, isDisabled && styles.disabled]}>{label}</Text>
      </Pressable>
    );
  }

  return (
    <Pressable
      onPress={onPress}
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
