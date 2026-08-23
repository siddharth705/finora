import { useState } from 'react';
import {
  KeyboardAvoidingView, Modal, Platform, Pressable, ScrollView, StyleSheet, Text, View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Button } from '../../components/Button';
import { TextField } from '../../components/TextField';
import { emailChangeApi } from '../../api/endpoints';
import { toUserMessage } from '../../lib/apiError';
import { EMAIL_PATTERN } from '../../lib/validation';
import { useSingleFlight } from '../../lib/useSingleFlight';
import { radius, spacing, useTheme } from '../../theme';

/**
 * Phase 4, ported from frontend/src/components/ChangeEmailModal.tsx. Unlike ChangePasswordSheet,
 * this sheet never reaches a "verified, commit now" step itself -- verify()/complete() run from
 * the link EmailChangeService emails to the NEW address (that's what proves control of it), on
 * VerifyEmailChangeScreen, reached via the deep link registered in RootNavigator. This sheet's job
 * ends at "we sent a link".
 *
 * Password-only step-up for now: unlike web's ChangeEmailModal (which branches on signInMethod to
 * offer Google reauth), no mobile settings flow -- including ChangePasswordSheet, the closest
 * precedent -- has a Google-reauth step-up path yet. Scoping this the same way rather than being
 * the first to build one; add the GOOGLE branch once that groundwork exists for step-up generally.
 */
export function ChangeEmailSheet({ onClose }: { onClose: () => void }) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const singleFlight = useSingleFlight();

  const [step, setStep] = useState<'form' | 'sent'>('form');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [currentPassword, setCurrentPassword] = useState('');
  const [newEmail, setNewEmail] = useState('');
  const [sentToEmail, setSentToEmail] = useState('');
  const [devVerifyLink, setDevVerifyLink] = useState<string | null>(null);

  const emailValid = EMAIL_PATTERN.test(newEmail.trim());
  const canSubmit = currentPassword.length > 0 && emailValid;

  async function submit() {
    if (!canSubmit) return;
    setError(null);
    await singleFlight(async () => {
      setSubmitting(true);
      try {
        const res = await emailChangeApi.start(currentPassword, null, null, newEmail.trim());
        setSentToEmail(newEmail.trim());
        setDevVerifyLink(res.devVerifyLink);
        setStep('sent');
      } catch (e) {
        setError(toUserMessage(e, 'Could not start the email change. Please try again.'));
      } finally {
        setSubmitting(false);
      }
    });
  }

  // Closing mid-request would leave the user unsure whether the change was started; on the "sent"
  // step the sheet has its own Done button and the backdrop is not a way out, same as
  // ChangePasswordSheet's success step.
  const dismissable = !submitting && step !== 'sent';

  return (
    <Modal visible animationType="slide" transparent onRequestClose={dismissable ? onClose : () => {}}>
      <KeyboardAvoidingView style={styles.flex} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
        <Pressable
          style={styles.backdrop}
          onPress={dismissable ? onClose : undefined}
          disabled={!dismissable}
          accessibilityLabel="Close change email"
        />
        <View style={[styles.sheet, { backgroundColor: c.card, paddingBottom: insets.bottom + spacing.md }]}>
          <ScrollView keyboardShouldPersistTaps="handled" style={styles.scroll}>
            {step === 'sent' ? (
              <View style={styles.successBlock}>
                <Text style={[styles.successMark, { color: c.success }]}>✓</Text>
                <Text style={[styles.title, { color: c.ink }]}>Check your inbox</Text>
                <Text style={[styles.body, { color: c.muted }]}>
                  We sent a confirmation link to {sentToEmail}. Tap it to finish changing your
                  email.
                </Text>
                {/* devVerifyLink mirrors ChangePasswordSheet's own environment-only affordances --
                    populated only when no email provider is configured (see EmailChangeDtos'
                    StartResponse doc comment). Shown as plain, copyable text rather than a link:
                    tapping it here would just open the system browser, not this app, since the
                    https:// verify path isn't a registered deep link (see RootNavigator's linking
                    config comment on why) -- copy it into a browser instead, or use the "Open in
                    the Finora app" link the web confirmation page itself offers. */}
                {devVerifyLink ? (
                  <Text selectable style={[styles.devLink, { color: c.primary }]}>{devVerifyLink}</Text>
                ) : null}
                <View style={styles.action}>
                  <Button label="Done" onPress={onClose} />
                </View>
              </View>
            ) : (
              <>
                <Text style={[styles.title, { color: c.ink }]}>Change Email</Text>
                <Text style={[styles.body, { color: c.muted }]}>
                  Enter your current password and the new email address. We&apos;ll send a
                  confirmation link to the new address before anything changes.
                </Text>

                <TextField
                  label="New email address"
                  value={newEmail}
                  onChangeText={(v) => { setNewEmail(v); setError(null); }}
                  autoCapitalize="none"
                  autoCorrect={false}
                  keyboardType="email-address"
                  textContentType="emailAddress"
                />

                <TextField
                  label="Current password"
                  value={currentPassword}
                  onChangeText={setCurrentPassword}
                  secure
                  autoCapitalize="none"
                  textContentType="password"
                />

                {error ? <Text style={[styles.error, { color: c.danger }]}>{error}</Text> : null}

                <View style={styles.action}>
                  <Button
                    label={submitting ? 'Sending…' : 'Send confirmation link'}
                    onPress={() => void submit()}
                    loading={submitting}
                    disabled={!canSubmit}
                  />
                </View>
                <Button label="Cancel" variant="link" onPress={onClose} disabled={!dismissable} />
              </>
            )}
          </ScrollView>
        </View>
      </KeyboardAvoidingView>
    </Modal>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.35)' },
  sheet: {
    maxHeight: '88%',
    borderTopLeftRadius: radius.xl,
    borderTopRightRadius: radius.xl,
    paddingHorizontal: spacing.md,
    paddingTop: spacing.md,
  },
  scroll: { flexGrow: 0 },
  title: { fontSize: 17, fontWeight: '700', marginBottom: 4 },
  body: { fontSize: 13, lineHeight: 19, marginBottom: spacing.md },
  error: { fontSize: 13, marginBottom: spacing.sm },
  action: { marginTop: spacing.sm, gap: spacing.xs },
  devLink: { fontSize: 11, marginTop: spacing.sm },
  successBlock: { alignItems: 'center', paddingVertical: spacing.lg },
  successMark: { fontSize: 34, fontWeight: '700', marginBottom: spacing.sm },
});
