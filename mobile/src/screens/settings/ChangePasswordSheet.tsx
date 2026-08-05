import { useState } from 'react';
import {
  KeyboardAvoidingView, Modal, Platform, Pressable, ScrollView, StyleSheet, Text, View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Button } from '../../components/Button';
import { ProgressBar } from '../../components/ProgressBar';
import { TextField } from '../../components/TextField';
import { passwordChangeApi } from '../../api/endpoints';
import { safeStorage } from '../../lib/safeStorage';
import { toUserMessage } from '../../lib/apiError';
import {
  confirmPhoneVerificationCode, sendPhoneVerificationCode, type PhoneConfirmation,
} from '../../lib/phoneAuth';
import { useSingleFlight } from '../../lib/useSingleFlight';
import { sanitizeOtp } from '../../lib/validation';
import { radius, spacing, useTheme } from '../../theme';

/**
 * The authenticated, OTP-gated Change Password flow, ported from
 * frontend/src/components/ChangePasswordModal.tsx.
 *
 * Current password -> code sent to the phone on file -> new password. Each step is validated
 * server-side against a persisted session (backend PasswordChangeService) rather than trusted from
 * whatever step this component thinks it is on.
 *
 * Deliberately a separate journey from Forgot Password: that one assumes the user CANNOT log in
 * and proves identity from scratch; this assumes they are logged in and know the current password.
 *
 * Two things the web version needs that this does not: a reCAPTCHA anchor element, and the
 * resetPhoneVerification() lifecycle around it. @react-native-firebase/auth verifies the app
 * natively, so the entire class of auth/argument-error retry bugs documented in that file cannot
 * occur here (see src/lib/phoneAuth.ts).
 *
 * The device completing this flow is never signed out -- the "sign out other devices" choice only
 * affects OTHER sessions -- so success simply closes the sheet.
 */
type Step = 'password' | 'otp' | 'newPassword' | 'success';

const REQUIREMENTS: { label: string; hint: string; test: (pw: string) => boolean }[] = [
  { label: 'At least 8 characters', hint: 'Make it at least 8 characters long', test: (pw) => pw.length >= 8 },
  { label: 'An uppercase letter', hint: 'Add an uppercase letter', test: (pw) => /[A-Z]/.test(pw) },
  { label: 'A lowercase letter', hint: 'Add a lowercase letter', test: (pw) => /[a-z]/.test(pw) },
  { label: 'A number', hint: 'Add a number', test: (pw) => /[0-9]/.test(pw) },
  { label: 'A special character', hint: 'Add a special character', test: (pw) => /[^A-Za-z0-9]/.test(pw) },
];

// Only length is actually enforced server-side (see AuthDtos.PASSWORD_SIZE_MESSAGE); the rest is a
// strength guide, not a gate, so this never blocks a password the backend would accept.
export function passwordStrengthMeter(pw: string): { label: string; pct: number; tone: 'weak' | 'good' | 'strong' | 'none' } {
  if (pw.length === 0) return { label: '', pct: 0, tone: 'none' };
  const met = REQUIREMENTS.filter((r) => r.test(pw)).length;
  if (met <= 2) return { label: 'Weak', pct: 33, tone: 'weak' };
  if (met <= 4) return { label: 'Good', pct: 66, tone: 'good' };
  return { label: 'Strong', pct: 100, tone: 'strong' };
}

/** The first unmet requirement, phrased as the next concrete step -- "Weak" alone tells nobody
 *  what to actually do about it. */
export function nextPasswordSuggestion(pw: string): string | null {
  const unmet = REQUIREMENTS.find((r) => !r.test(pw));
  return unmet ? `${unmet.hint} to improve strength.` : null;
}

export function ChangePasswordSheet({ onClose, onSuccess }: {
  onClose: () => void;
  onSuccess?: () => void;
}) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const singleFlight = useSingleFlight();

  const [step, setStep] = useState<Step>('password');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [currentPassword, setCurrentPassword] = useState('');
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [maskedPhone, setMaskedPhone] = useState('');
  const [confirmation, setConfirmation] = useState<PhoneConfirmation | null>(null);
  const [otp, setOtp] = useState('');

  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [signOutOtherDevices, setSignOutOtherDevices] = useState(true);
  const [successMessage, setSuccessMessage] = useState('');

  const meter = passwordStrengthMeter(newPassword);
  const suggestion = nextPasswordSuggestion(newPassword);
  const confirmMismatch = confirmPassword.length > 0 && confirmPassword !== newPassword;
  const otpValid = /^\d{6}$/.test(otp);
  const canSubmitNewPassword = newPassword.length >= 8 && newPassword === confirmPassword;

  async function submitCurrentPassword() {
    if (currentPassword.length === 0) return;
    setError(null);
    await singleFlight(async () => {
      setSubmitting(true);
      try {
        const res = await passwordChangeApi.start(currentPassword);
        setSessionId(res.sessionId);
        setMaskedPhone(res.maskedPhone);
        // Firebase sends the code itself, straight to the number this response reveals -- the
        // backend never does (see PasswordChangeService.start's own doc comment).
        setConfirmation(await sendPhoneVerificationCode(res.phoneNumber));
        setStep('otp');
      } catch (e) {
        setError(toUserMessage(e, 'Could not start the password change. Please try again.'));
      } finally {
        setSubmitting(false);
      }
    });
  }

  async function submitOtp() {
    if (!sessionId || !confirmation || !otpValid) return;
    setError(null);
    await singleFlight(async () => {
      setSubmitting(true);
      try {
        const idToken = await confirmPhoneVerificationCode(confirmation, otp);
        await passwordChangeApi.verifyOtp(sessionId, idToken);
        setStep('newPassword');
      } catch (e) {
        // Covers Firebase (confirm) and the backend (verifyOtp) alike -- toUserMessage maps both.
        setError(toUserMessage(e, 'Could not verify that code. Please try again.'));
        setOtp('');
      } finally {
        setSubmitting(false);
      }
    });
  }

  /** Starting over re-verifies the current password and issues a fresh code. There is no separate
   *  resend endpoint: a new code always requires re-proving current-password ownership first. */
  function startOver() {
    setStep('password');
    setOtp('');
    setSessionId(null);
    setConfirmation(null);
    setError(null);
  }

  async function submitNewPassword() {
    if (!sessionId || !canSubmitNewPassword) return;
    setError(null);
    await singleFlight(async () => {
      setSubmitting(true);
      try {
        // Sent so the backend knows which session to SPARE when signing out the others -- this
        // device stays signed in either way (see CompleteRequest.currentRefreshToken).
        const currentRefreshToken = await safeStorage.getItem('finora_refresh_token');
        if (!currentRefreshToken) {
          setError('Your session information is missing. Please sign in again and retry.');
          return;
        }
        const res = await passwordChangeApi.complete(
          sessionId, newPassword, signOutOtherDevices, currentRefreshToken
        );
        setSuccessMessage(res.message);
        setStep('success');
        onSuccess?.();
      } catch (e) {
        setError(toUserMessage(e, 'Could not update your password. Please try again.'));
      } finally {
        setSubmitting(false);
      }
    });
  }

  const meterColor = meter.tone === 'strong' ? c.success : meter.tone === 'good' ? c.warning : c.danger;
  // Closing mid-request would leave the user unsure whether the change went through; on the
  // success step the sheet has its own Done button and the backdrop is not a way out.
  const dismissable = !submitting && step !== 'success';

  return (
    <Modal visible animationType="slide" transparent onRequestClose={dismissable ? onClose : () => {}}>
      <KeyboardAvoidingView style={styles.flex} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
        <Pressable
          style={styles.backdrop}
          onPress={dismissable ? onClose : undefined}
          disabled={!dismissable}
          accessibilityLabel="Close change password"
        />
        <View style={[styles.sheet, { backgroundColor: c.card, paddingBottom: insets.bottom + spacing.md }]}>
          <ScrollView keyboardShouldPersistTaps="handled" style={styles.scroll}>
            {step === 'success' ? (
              <View style={styles.successBlock}>
                <Text style={[styles.successMark, { color: c.success }]}>✓</Text>
                <Text style={[styles.title, { color: c.ink }]}>Password updated</Text>
                <Text style={[styles.body, { color: c.muted }]}>{successMessage}</Text>
                <View style={styles.action}>
                  <Button label="Done" onPress={onClose} />
                </View>
              </View>
            ) : (
              <>
                <Text style={[styles.title, { color: c.ink }]}>Change Password</Text>

                {step === 'password' ? (
                  <>
                    <Text style={[styles.body, { color: c.muted }]}>
                      Enter your current password to get started. We&apos;ll send a verification code
                      to the phone on file.
                    </Text>
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
                        label={submitting ? 'Sending…' : 'Send code'}
                        onPress={() => void submitCurrentPassword()}
                        loading={submitting}
                        disabled={currentPassword.length === 0}
                      />
                    </View>
                  </>
                ) : null}

                {step === 'otp' ? (
                  <>
                    <Text style={[styles.body, { color: c.muted }]}>
                      Enter the 6-digit code sent to {maskedPhone}.
                    </Text>
                    <TextField
                      label="Verification code"
                      value={otp}
                      // No maxLength: React Native truncates a pasted "Your code is 123456" before
                      // sanitizeOtp can pull the digits out. Same rule as RegisterScreen's phone
                      // field -- see sanitizeOtp's own comment.
                      onChangeText={(v) => { setOtp(sanitizeOtp(v)); setError(null); }}
                      keyboardType="number-pad"
                      textContentType="oneTimeCode"
                      autoComplete="sms-otp"
                      placeholder="123456"
                    />
                    {error ? <Text style={[styles.error, { color: c.danger }]}>{error}</Text> : null}
                    <View style={styles.action}>
                      <Button
                        label={submitting ? 'Verifying…' : 'Verify'}
                        onPress={() => void submitOtp()}
                        loading={submitting}
                        disabled={!otpValid}
                      />
                      <Button label="Didn't get a code? Start over" variant="link" onPress={startOver} />
                    </View>
                  </>
                ) : null}

                {step === 'newPassword' ? (
                  <>
                    <TextField
                      label="New password"
                      value={newPassword}
                      onChangeText={setNewPassword}
                      secure
                      autoCapitalize="none"
                      textContentType="newPassword"
                    />
                    {newPassword.length > 0 ? (
                      <View style={styles.meter}>
                        <ProgressBar pct={meter.pct} color={meterColor} />
                        <Text style={[styles.meterLabel, { color: c.muted }]}>
                          {meter.label}
                          {suggestion && meter.tone !== 'strong' ? ` — ${suggestion}` : ''}
                        </Text>
                      </View>
                    ) : null}
                    <TextField
                      label="Confirm new password"
                      value={confirmPassword}
                      onChangeText={setConfirmPassword}
                      secure
                      autoCapitalize="none"
                      textContentType="newPassword"
                      error={confirmMismatch ? "Passwords don't match." : null}
                    />

                    <Text style={[styles.groupLabel, { color: c.muted }]}>Other devices</Text>
                    {[
                      { value: true, label: 'Sign out other devices (Recommended) — this device stays signed in.' },
                      { value: false, label: 'Keep other devices signed in' },
                    ].map((option) => (
                      <Pressable
                        key={String(option.value)}
                        onPress={() => setSignOutOtherDevices(option.value)}
                        style={[styles.radioRow, { borderColor: c.border }]}
                        accessibilityRole="radio"
                        accessibilityState={{ checked: signOutOtherDevices === option.value }}
                        accessibilityLabel={option.label}
                      >
                        <Text style={[styles.radioMark, { color: c.primary }]}>
                          {signOutOtherDevices === option.value ? '◉' : '○'}
                        </Text>
                        <Text style={[styles.radioLabel, { color: c.ink }]}>{option.label}</Text>
                      </Pressable>
                    ))}

                    {error ? <Text style={[styles.error, { color: c.danger }]}>{error}</Text> : null}
                    <View style={styles.action}>
                      <Button
                        label={submitting ? 'Updating…' : 'Update Password'}
                        onPress={() => void submitNewPassword()}
                        loading={submitting}
                        disabled={!canSubmitNewPassword}
                      />
                    </View>
                  </>
                ) : null}

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
  meter: { marginBottom: spacing.sm },
  meterLabel: { fontSize: 11, marginTop: 4 },
  groupLabel: { fontSize: 12, fontWeight: '500', marginTop: spacing.sm, marginBottom: 6 },
  radioRow: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: spacing.sm,
    borderWidth: 1,
    borderRadius: radius.md,
    padding: 12,
    marginBottom: spacing.xs,
    minHeight: 44,
  },
  radioMark: { fontSize: 15 },
  radioLabel: { fontSize: 12, flex: 1, lineHeight: 17 },
  successBlock: { alignItems: 'center', paddingVertical: spacing.lg },
  successMark: { fontSize: 34, fontWeight: '700', marginBottom: spacing.sm },
});
