import { useState } from 'react';
import { StyleSheet, Text } from 'react-native';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { AuthScreenLayout } from '../components/AuthScreenLayout';
import { Button } from '../components/Button';
import { TextField } from '../components/TextField';
import { authApi } from '../api/endpoints';
import { spacing, useTheme } from '../theme';
import type { AuthStackParamList } from '../navigation/types';

type Props = NativeStackScreenProps<AuthStackParamList, 'ForgotPassword'>;

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/**
 * Requests the reset email and stops there. Completing a reset happens in the web app: the link
 * the backend emails points at APP_BASE_URL (the web frontend), and the reset itself needs a
 * second Firebase OTP step against a token only that page holds. Handling it in-app would mean
 * deep links plus a duplicate reset flow -- deferred until there's evidence the web hand-off is
 * actually friction (see the roadmap's Phase 1 note).
 *
 * Deliberately omits the web version's devResetLink display. That link is a live
 * account-takeover primitive the backend only returns when RESEND_API_KEY is unset, which
 * ProductionConfigValidator already refuses to allow in prod; showing it in a shipped mobile
 * binary has no dev-convenience payoff to justify it.
 */
export function ForgotPasswordScreen({ navigation }: Props) {
  const c = useTheme();
  const [email, setEmail] = useState('');
  const [touched, setTouched] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const emailValid = EMAIL_PATTERN.test(email.trim());

  async function handleSubmit() {
    setTouched(true);
    setError(null);
    if (!emailValid) {
      setError('Enter a valid email address.');
      return;
    }
    setLoading(true);
    try {
      await authApi.forgotPassword(email.trim());
      setSubmitted(true);
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'Something went wrong. Try again.');
    } finally {
      setLoading(false);
    }
  }

  if (submitted) {
    return (
      <AuthScreenLayout title="Check your email">
        <Text style={[styles.body, { color: c.muted }]}>
          If an account exists for {email.trim()}, a reset link has been sent. Open it on the web to
          choose a new password, then come back here to sign in.
        </Text>
        <Button label="Back to sign in" onPress={() => navigation.navigate('Login')} />
      </AuthScreenLayout>
    );
  }

  return (
    <AuthScreenLayout
      title="Reset your password"
      subtitle="Enter your email and we'll send you a reset link."
      error={error}
      footer={<Button label="Back to sign in" variant="link" onPress={() => navigation.navigate('Login')} />}
    >
      <TextField
        label="Email"
        value={email}
        onChangeText={setEmail}
        onBlur={() => setTouched(true)}
        placeholder="you@example.com"
        autoCapitalize="none"
        autoCorrect={false}
        autoComplete="email"
        keyboardType="email-address"
        returnKeyType="go"
        onSubmitEditing={handleSubmit}
        error={touched && !emailValid ? 'Enter a valid email address.' : null}
      />

      <Button label="Send reset link" onPress={handleSubmit} loading={loading} disabled={!emailValid} />
    </AuthScreenLayout>
  );
}

const styles = StyleSheet.create({
  body: {
    fontSize: 13,
    lineHeight: 20,
    marginBottom: spacing.md,
  },
});
