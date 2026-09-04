import { useState } from 'react';
import {
  KeyboardAvoidingView, Modal, Platform, Pressable, ScrollView, StyleSheet, Text, TextInput, View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Button } from '../../components/Button';
import { OptionPickerModal } from '../../components/OptionPickerModal';
import { TextField } from '../../components/TextField';
import { supportApi, type RNFile, type SupportTicketCategory, type SupportTicketDetail } from '../../api/endpoints';
import { toUserMessage } from '../../lib/apiError';
import { AttachmentTooLargeError, pickTicketAttachment } from '../../lib/ticketAttachment';
import { useSingleFlight } from '../../lib/useSingleFlight';
import { radius, spacing, useTheme } from '../../theme';

const CATEGORIES: { value: SupportTicketCategory; label: string }[] = [
  { value: 'STATEMENT_IMPORT', label: 'Statement import' },
  { value: 'CATEGORIZATION', label: 'Transaction categorization' },
  { value: 'ACCOUNT_LINKING', label: 'Account linking' },
  { value: 'DATA_ACCURACY', label: 'Data accuracy' },
  { value: 'TECHNICAL_ISSUE', label: 'Technical issue' },
  { value: 'OTHER', label: 'Something else' },
];
const CATEGORY_LABEL_TO_VALUE = new Map(CATEGORIES.map((c) => [c.label, c.value]));

/**
 * Support, Help & Feedback v1, Phase 8 (mobile). Submits straight to
 * SupportTicketController.create() -- ported from frontend's NewTicketModal.tsx, same reasoning
 * for why this needs an authenticated caller (there's no public/marketing surface on mobile to
 * even consider putting it on).
 */
export function NewTicketSheet({ onClose, onCreated }: {
  onClose: () => void;
  onCreated: (ticket: SupportTicketDetail) => void;
}) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const singleFlight = useSingleFlight();

  const [category, setCategory] = useState<SupportTicketCategory>('STATEMENT_IMPORT');
  const [categoryPickerOpen, setCategoryPickerOpen] = useState(false);
  const [subject, setSubject] = useState('');
  const [description, setDescription] = useState('');
  const [file, setFile] = useState<RNFile | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const categoryLabel = CATEGORIES.find((opt) => opt.value === category)!.label;
  const canSave = subject.trim().length > 0 && description.trim().length > 0 && !saving;

  async function chooseAttachment() {
    setFileError(null);
    try {
      const picked = await pickTicketAttachment();
      if (picked) setFile(picked);
    } catch (e) {
      setFile(null);
      setFileError(e instanceof AttachmentTooLargeError ? e.message : 'Could not read that file.');
    }
  }

  async function save() {
    if (!canSave) return;
    setError(null);
    await singleFlight(async () => {
      setSaving(true);
      try {
        const ticket = await supportApi.create({
          category, subject: subject.trim(), description: description.trim(), file,
        });
        onCreated(ticket);
      } catch (e) {
        setError(toUserMessage(e, 'Could not submit the ticket.'));
      } finally {
        setSaving(false);
      }
    });
  }

  return (
    <Modal visible animationType="slide" transparent onRequestClose={saving ? () => {} : onClose}>
      <KeyboardAvoidingView style={styles.flex} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
        <Pressable
          style={styles.backdrop}
          onPress={saving ? undefined : onClose}
          disabled={saving}
          accessibilityLabel="Close new ticket"
        />
        <View style={[styles.sheet, { backgroundColor: c.card, paddingBottom: insets.bottom + spacing.md }]}>
          <ScrollView keyboardShouldPersistTaps="handled" style={styles.scroll}>
            <Text style={[styles.title, { color: c.ink }]}>New support ticket</Text>

            {error ? <Text style={[styles.error, { color: c.danger }]}>{error}</Text> : null}

            <Text style={[styles.fieldLabel, { color: c.muted }]}>Category</Text>
            <Pressable
              onPress={() => setCategoryPickerOpen(true)}
              style={[styles.picker, { backgroundColor: c.inputBg, borderColor: c.border }]}
              accessibilityRole="button"
              accessibilityLabel={`Category: ${categoryLabel}. Change`}
            >
              <Text style={[styles.pickerText, { color: c.ink }]}>{categoryLabel}</Text>
              <Text style={[styles.chevron, { color: c.muted }]} accessibilityElementsHidden importantForAccessibility="no">›</Text>
            </Pressable>

            <TextField
              label="Subject"
              value={subject}
              onChangeText={setSubject}
              placeholder="A short summary of the issue"
            />

            <Text style={[styles.fieldLabel, { color: c.muted }]}>Description</Text>
            <TextInput
              value={description}
              onChangeText={setDescription}
              placeholder="What happened, and what did you expect instead?"
              placeholderTextColor={c.muted}
              multiline
              numberOfLines={5}
              textAlignVertical="top"
              accessibilityLabel="Description"
              style={[styles.textarea, { color: c.ink, backgroundColor: c.inputBg, borderColor: c.border }]}
            />

            <Text style={[styles.fieldLabel, { color: c.muted, marginTop: spacing.sm }]}>
              Attachment (optional — PDF, PNG, JPEG or text, up to 5 MB)
            </Text>
            <Pressable
              onPress={() => void chooseAttachment()}
              style={[styles.picker, { backgroundColor: c.inputBg, borderColor: c.border }]}
              accessibilityRole="button"
              accessibilityLabel={file ? `Attachment: ${file.name}. Change` : 'Choose an attachment'}
            >
              <Text style={[styles.pickerText, { color: file ? c.ink : c.muted }]} numberOfLines={1}>
                {file ? file.name : 'Choose a file (e.g. a screenshot of the problem)'}
              </Text>
            </Pressable>
            {fileError ? <Text style={[styles.error, { color: c.danger }]}>{fileError}</Text> : null}

            <View style={styles.action}>
              <Button label={saving ? 'Submitting…' : 'Submit ticket'} onPress={() => void save()} loading={saving} disabled={!canSave} />
              <Button label="Cancel" variant="link" onPress={onClose} disabled={saving} />
            </View>
          </ScrollView>
        </View>
      </KeyboardAvoidingView>

      <OptionPickerModal
        visible={categoryPickerOpen}
        title="Category"
        options={CATEGORIES.map((opt) => opt.label)}
        selected={categoryLabel}
        onSelect={(label) => {
          const value = CATEGORY_LABEL_TO_VALUE.get(label);
          if (value) setCategory(value);
          setCategoryPickerOpen(false);
        }}
        onClose={() => setCategoryPickerOpen(false)}
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
  picker: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    borderWidth: 1, borderRadius: radius.md, paddingHorizontal: 12, minHeight: 48, marginBottom: spacing.xs,
  },
  pickerText: { fontSize: 15, flex: 1, marginRight: spacing.sm },
  chevron: { fontSize: 20, lineHeight: 20 },
  textarea: {
    borderWidth: 1, borderRadius: radius.md, paddingHorizontal: 12, paddingVertical: 12,
    fontSize: 15, minHeight: 100, marginBottom: spacing.xs,
  },
  action: { marginTop: spacing.sm, gap: spacing.xs },
});
