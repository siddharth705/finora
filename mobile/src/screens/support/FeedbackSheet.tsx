import { useState } from 'react';
import {
  KeyboardAvoidingView, Modal, Platform, Pressable, ScrollView, StyleSheet, Text, TextInput, View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Button } from '../../components/Button';
import { OptionPickerModal } from '../../components/OptionPickerModal';
import { feedbackApi, type FeedbackContext, type FeedbackType } from '../../api/endpoints';
import { toUserMessage } from '../../lib/apiError';
import { useSingleFlight } from '../../lib/useSingleFlight';
import { radius, spacing, useTheme } from '../../theme';

const TYPES: { value: FeedbackType; label: string }[] = [
  { value: 'BUG', label: 'Something’s broken' },
  { value: 'FEATURE_REQUEST', label: 'A feature I’d like' },
  { value: 'IMPROVEMENT', label: 'An improvement idea' },
  { value: 'GENERAL', label: 'General feedback' },
];

// On web, FeedbackModal derives `context` automatically from the current route (one widget,
// mounted once in TopBar, present on every page). Mobile has no equivalent of "the page this is
// currently open on top of" -- this sheet is reached from exactly one place (Settings), so the
// route it opened from would always say the same thing (SETTINGS) regardless of what the
// feedback is actually about, which defeats the whole point of the field: "which feature the
// feedback came from -- the aggregation axis this table exists to serve" (FeedbackEntry.Context's
// own doc comment). Asking directly serves that purpose better here than a mount-point guess
// would.
const CONTEXTS: { value: FeedbackContext; label: string }[] = [
  { value: 'DASHBOARD', label: 'Dashboard' },
  { value: 'TRANSACTIONS', label: 'Transactions' },
  { value: 'REPORTS', label: 'Reports' },
  { value: 'BUDGETS', label: 'Budgets' },
  { value: 'GOALS', label: 'Goals' },
  { value: 'IMPORT_FLOW', label: 'Importing a statement' },
  { value: 'ACCOUNTS', label: 'Accounts' },
  { value: 'SETTINGS', label: 'Settings' },
  { value: 'HELP', label: 'Help' },
  { value: 'OTHER', label: 'Something else' },
];
const CONTEXT_LABEL_TO_VALUE = new Map(CONTEXTS.map((c) => [c.label, c.value]));

/** Support, Help & Feedback v1, Phase 8 (mobile). Ported from frontend's FeedbackModal.tsx --
 *  FeedbackController.submit() has existed since the backend phases with nothing calling it. */
export function FeedbackSheet({ onClose }: { onClose: () => void }) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const singleFlight = useSingleFlight();

  const [type, setType] = useState<FeedbackType>('GENERAL');
  const [context, setContext] = useState<FeedbackContext>('OTHER');
  const [contextPickerOpen, setContextPickerOpen] = useState(false);
  const [message, setMessage] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sent, setSent] = useState(false);

  const contextLabel = CONTEXTS.find((ctx) => ctx.value === context)!.label;
  const canSave = message.trim().length > 0 && !saving;

  async function save() {
    if (!canSave) return;
    setError(null);
    await singleFlight(async () => {
      setSaving(true);
      try {
        await feedbackApi.submit({ type, context, message: message.trim() });
        setSent(true);
      } catch (e) {
        setError(toUserMessage(e, 'Could not send this feedback.'));
      } finally {
        setSaving(false);
      }
    });
  }

  return (
    <Modal visible animationType="slide" transparent onRequestClose={saving ? () => {} : onClose}>
      <KeyboardAvoidingView style={styles.flex} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
        <Pressable style={styles.backdrop} onPress={saving ? undefined : onClose} disabled={saving} accessibilityLabel="Close send feedback" />
        <View style={[styles.sheet, { backgroundColor: c.card, paddingBottom: insets.bottom + spacing.md }]}>
          <ScrollView keyboardShouldPersistTaps="handled" style={styles.scroll}>
            <Text style={[styles.title, { color: c.ink }]}>Send feedback</Text>

            {sent ? (
              <View style={styles.successBlock}>
                <Text style={[styles.successMark, { color: c.success }]}>✓</Text>
                <Text style={[styles.successTitle, { color: c.ink }]}>Thanks for the feedback</Text>
                <Text style={[styles.successBody, { color: c.muted }]}>We read every submission.</Text>
                <View style={styles.action}>
                  <Button label="Close" onPress={onClose} />
                </View>
              </View>
            ) : (
              <>
                {error ? <Text style={[styles.error, { color: c.danger }]}>{error}</Text> : null}

                <Text style={[styles.fieldLabel, { color: c.muted }]}>What kind of feedback?</Text>
                <View style={styles.typeRow}>
                  {TYPES.map((t) => {
                    const active = t.value === type;
                    return (
                      <Pressable
                        key={t.value}
                        onPress={() => setType(t.value)}
                        style={[styles.typeChip, { borderColor: active ? c.primary : c.border, backgroundColor: active ? c.primaryLight : c.inputBg }]}
                        // radio/checked, not button/selected -- matches ChangePasswordSheet's
                        // "Other devices" row, the established pattern for a mutually-exclusive
                        // single-choice group in this codebase.
                        accessibilityRole="radio"
                        accessibilityState={{ checked: active }}
                      >
                        <Text style={[styles.typeChipText, { color: active ? c.primary : c.ink }]}>{t.label}</Text>
                      </Pressable>
                    );
                  })}
                </View>

                <Text style={[styles.fieldLabel, { color: c.muted, marginTop: spacing.sm }]}>What&apos;s it about?</Text>
                <Pressable
                  onPress={() => setContextPickerOpen(true)}
                  style={[styles.picker, { backgroundColor: c.inputBg, borderColor: c.border }]}
                  accessibilityRole="button"
                  accessibilityLabel={`About: ${contextLabel}. Change`}
                >
                  <Text style={[styles.pickerText, { color: c.ink }]}>{contextLabel}</Text>
                  <Text style={[styles.chevron, { color: c.muted }]} accessibilityElementsHidden importantForAccessibility="no">›</Text>
                </Pressable>

                <Text style={[styles.fieldLabel, { color: c.muted, marginTop: spacing.sm }]}>Your feedback</Text>
                <TextInput
                  value={message}
                  onChangeText={setMessage}
                  placeholder="What's on your mind?"
                  placeholderTextColor={c.muted}
                  multiline
                  numberOfLines={4}
                  textAlignVertical="top"
                  accessibilityLabel="Your feedback"
                  style={[styles.textarea, { color: c.ink, backgroundColor: c.inputBg, borderColor: c.border }]}
                />

                <View style={styles.action}>
                  <Button label={saving ? 'Sending…' : 'Send feedback'} onPress={() => void save()} loading={saving} disabled={!canSave} />
                  <Button label="Cancel" variant="link" onPress={onClose} disabled={saving} />
                </View>
              </>
            )}
          </ScrollView>
        </View>
      </KeyboardAvoidingView>

      <OptionPickerModal
        visible={contextPickerOpen}
        title="What's it about?"
        options={CONTEXTS.map((ctx) => ctx.label)}
        selected={contextLabel}
        onSelect={(label) => {
          const value = CONTEXT_LABEL_TO_VALUE.get(label);
          if (value) setContext(value);
          setContextPickerOpen(false);
        }}
        onClose={() => setContextPickerOpen(false)}
      />
    </Modal>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.35)' },
  sheet: {
    maxHeight: '90%',
    borderTopLeftRadius: radius.xl,
    borderTopRightRadius: radius.xl,
    paddingHorizontal: spacing.md,
    paddingTop: spacing.md,
  },
  scroll: { flexGrow: 0 },
  title: { fontSize: 17, fontWeight: '700', marginBottom: spacing.sm },
  error: { fontSize: 13, marginBottom: spacing.sm },
  fieldLabel: { fontSize: 12, fontWeight: '500', marginBottom: 6 },
  typeRow: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs },
  typeChip: { borderWidth: 1, borderRadius: radius.xl, paddingHorizontal: 12, paddingVertical: 8 },
  typeChipText: { fontSize: 12, fontWeight: '600' },
  picker: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    borderWidth: 1, borderRadius: radius.md, paddingHorizontal: 12, minHeight: 48,
  },
  pickerText: { fontSize: 15, flex: 1, marginRight: spacing.sm },
  chevron: { fontSize: 20, lineHeight: 20 },
  textarea: {
    borderWidth: 1, borderRadius: radius.md, paddingHorizontal: 12, paddingVertical: 12,
    fontSize: 15, minHeight: 90,
  },
  action: { marginTop: spacing.md, gap: spacing.xs },
  successBlock: { alignItems: 'center', paddingVertical: spacing.lg },
  successMark: { fontSize: 30, fontWeight: '700', marginBottom: spacing.xs },
  successTitle: { fontSize: 15, fontWeight: '600' },
  successBody: { fontSize: 12, marginTop: 2 },
});
