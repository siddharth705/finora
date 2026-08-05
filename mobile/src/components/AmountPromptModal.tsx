import { useState } from 'react';
import {
  KeyboardAvoidingView, Modal, Platform, Pressable, StyleSheet, Text, TextInput, View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { parsePositiveAmount } from '../lib/validation';
import { radius, spacing, useTheme } from '../theme';

/**
 * The native replacement for the web Goals page's `window.prompt('Contribution amount:')`, which
 * has no React Native equivalent at all -- so this is a required substitution, not a style choice.
 *
 * It is also a better control than the thing it replaces: `prompt()` gives no numeric keyboard, no
 * currency context, and no way to reject bad input without a second dialog. Here the field is
 * numeric, invalid input is refused inline, and the confirm button stays disabled until the amount
 * is actually usable.
 *
 * Mount it only while it's open (`{target ? <AmountPromptModal … /> : null}`) rather than keeping
 * it mounted behind a `visible` flag: the typed amount then lives and dies with one open, so
 * reopening for a different row can't inherit the last one's value and nothing has to remember to
 * clear it.
 */
interface Props {
  title: string;
  /** Sits above the field -- e.g. the goal's name, so the sheet says what is being funded. */
  subtitle?: string;
  confirmLabel: string;
  /** Surfaced under the field when the caller's own submit fails (e.g. the request errored). */
  error?: string | null;
  submitting?: boolean;
  onSubmit: (amount: number) => void;
  onClose: () => void;
}

export function AmountPromptModal({
  title, subtitle, confirmLabel, error, submitting = false, onSubmit, onClose,
}: Props) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const [raw, setRaw] = useState('');

  const amount = parsePositiveAmount(raw);
  const canSubmit = amount !== null && !submitting;

  return (
    // Every route out is closed while the request is in flight -- the backdrop, the hardware back
    // key, and Cancel below. Letting any of them through would dismiss the sheet while the
    // contribution is still on its way to the server: it lands anyway, and the user last saw
    // themselves cancel it. Money must not move after an apparent cancel.
    <Modal visible animationType="slide" transparent onRequestClose={submitting ? () => {} : onClose}>
      <KeyboardAvoidingView style={styles.flex} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
        <Pressable
          style={styles.backdrop}
          onPress={submitting ? undefined : onClose}
          disabled={submitting}
          accessibilityLabel={`Close ${title}`}
        />
        <View style={[styles.sheet, { backgroundColor: c.card, paddingBottom: insets.bottom + spacing.md }]}>
          <Text style={[styles.title, { color: c.ink }]}>{title}</Text>
          {subtitle ? <Text style={[styles.subtitle, { color: c.muted }]}>{subtitle}</Text> : null}

          <View style={[styles.inputRow, { backgroundColor: c.inputBg, borderColor: error ? c.danger : c.border }]}>
            <Text style={[styles.currency, { color: c.muted }]}>₹</Text>
            <TextInput
              value={raw}
              onChangeText={setRaw}
              // decimal-pad, not numeric: numeric shows a full phone-style keypad on iOS with
              // letters and symbols this field can never accept.
              keyboardType="decimal-pad"
              placeholder="0"
              placeholderTextColor={c.muted}
              autoFocus
              style={[styles.input, { color: c.ink }]}
              accessibilityLabel="Amount in rupees"
              onSubmitEditing={() => { if (canSubmit) onSubmit(amount); }}
              returnKeyType="done"
            />
          </View>

          <Text style={[styles.error, { color: c.danger }]} numberOfLines={2}>
            {error ?? ''}
          </Text>

          <View style={styles.actions}>
            <Pressable
              onPress={onClose}
              disabled={submitting}
              style={[styles.button, styles.cancel, { borderColor: c.border }, submitting && styles.disabled]}
              accessibilityRole="button"
              accessibilityState={{ disabled: submitting }}
            >
              <Text style={[styles.cancelText, { color: c.muted }]}>Cancel</Text>
            </Pressable>
            <Pressable
              onPress={() => { if (canSubmit) onSubmit(amount); }}
              disabled={!canSubmit}
              style={[styles.button, { backgroundColor: c.primary }, !canSubmit && styles.disabled]}
              accessibilityRole="button"
              accessibilityState={{ disabled: !canSubmit, busy: submitting }}
            >
              <Text style={styles.confirmText}>{submitting ? 'Saving…' : confirmLabel}</Text>
            </Pressable>
          </View>
        </View>
      </KeyboardAvoidingView>
    </Modal>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.35)' },
  sheet: {
    borderTopLeftRadius: radius.xl,
    borderTopRightRadius: radius.xl,
    paddingHorizontal: spacing.md,
    paddingTop: spacing.md,
  },
  title: { fontSize: 17, fontWeight: '700' },
  subtitle: { fontSize: 13, marginTop: 2 },
  inputRow: {
    flexDirection: 'row',
    alignItems: 'center',
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: 12,
    minHeight: 48,
    marginTop: spacing.md,
  },
  currency: { fontSize: 17, marginRight: 6 },
  input: { flex: 1, fontSize: 17, paddingVertical: 12 },
  error: { fontSize: 11, minHeight: 16, marginTop: 4 },
  actions: { flexDirection: 'row', gap: spacing.sm, marginTop: spacing.sm },
  button: {
    flex: 1,
    borderRadius: radius.md,
    minHeight: 48,
    alignItems: 'center',
    justifyContent: 'center',
  },
  cancel: { borderWidth: 1 },
  cancelText: { fontSize: 15, fontWeight: '600' },
  confirmText: { color: '#fff', fontSize: 15, fontWeight: '600' },
  disabled: { opacity: 0.5 },
});
