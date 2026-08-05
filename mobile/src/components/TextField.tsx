import { useState } from 'react';
import { Pressable, StyleSheet, Text, TextInput, View, type TextInputProps } from 'react-native';
import { radius, spacing, useTheme } from '../theme';

interface Props extends Omit<TextInputProps, 'style' | 'secureTextEntry'> {
  label: string;
  value: string;
  onChangeText: (v: string) => void;
  /** Renders the show/hide toggle -- the mobile counterpart of the web's PasswordInput component. */
  secure?: boolean;
  /** Shown in danger color beneath the field. Space is always reserved so typing doesn't reflow. */
  error?: string | null;
  /** Non-editable text rendered inside the field's left edge, e.g. the "+91" dial-code prefix. */
  prefix?: string;
}

export function TextField({ label, value, onChangeText, secure = false, error, prefix, ...rest }: Props) {
  const c = useTheme();
  const [revealed, setRevealed] = useState(false);

  return (
    <View style={styles.wrap}>
      <Text style={[styles.label, { color: c.muted }]}>{label}</Text>
      <View
        style={[
          styles.inputRow,
          { backgroundColor: c.inputBg, borderColor: error ? c.danger : c.border },
        ]}
      >
        {prefix ? <Text style={[styles.prefix, { color: c.ink, borderRightColor: c.border }]}>{prefix}</Text> : null}
        <TextInput
          value={value}
          onChangeText={onChangeText}
          secureTextEntry={secure && !revealed}
          placeholderTextColor={c.muted}
          style={[styles.input, { color: c.ink }]}
          // The visible <Text> above is a sibling, not a linked <label> -- React Native has no
          // htmlFor. Without this the field announces as an unnamed "text field", and a form of
          // them is indistinguishable by ear. Overridable via ...rest for the rare field whose
          // spoken name should differ from its printed one.
          accessibilityLabel={label}
          {...rest}
        />
        {secure ? (
          <Pressable
            onPress={() => setRevealed((r) => !r)}
            hitSlop={8}
            accessibilityRole="button"
            accessibilityLabel={revealed ? 'Hide password' : 'Show password'}
          >
            <Text style={[styles.toggle, { color: c.primary }]}>{revealed ? 'Hide' : 'Show'}</Text>
          </Pressable>
        ) : null}
      </View>
      {/* Fixed-height slot so showing an error doesn't shift everything below it -- same idea as
          the web form's `h-3.5` error paragraphs. */}
      <Text style={[styles.error, { color: c.danger }]} numberOfLines={2}>
        {error ?? ''}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: {
    marginBottom: spacing.xs,
  },
  label: {
    fontSize: 12,
    fontWeight: '500',
    marginBottom: 6,
  },
  inputRow: {
    flexDirection: 'row',
    alignItems: 'center',
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: 12,
    minHeight: 48,
  },
  prefix: {
    fontSize: 15,
    paddingRight: 8,
    marginRight: 8,
    borderRightWidth: 1,
  },
  input: {
    flex: 1,
    fontSize: 15,
    paddingVertical: 12,
  },
  toggle: {
    fontSize: 13,
    fontWeight: '600',
    paddingLeft: 8,
  },
  error: {
    fontSize: 11,
    minHeight: 16,
    marginTop: 2,
  },
});
