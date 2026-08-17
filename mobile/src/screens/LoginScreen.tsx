import { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { AuthScreenLayout } from '../components/AuthScreenLayout';
import { Button } from '../components/Button';
import { TextField } from '../components/TextField';
import { useAuth } from '../context/AuthContext';
import { apiErrorCode, toUserMessage } from '../lib/apiError';
import { AUTH_ACCOUNT_DEACTIVATED } from '../api/errorCodes';
import { spacing, useTheme } from '../theme';
import type { AuthStackParamList } from '../navigation/types';

type Props = NativeStackScreenProps<AuthStackParamList, 'Login'>;

export function LoginScreen({ navigation, route }: Props) {
  const c = useTheme();
  const { login, reactivate } = useAuth();
  const [identifier, setIdentifier] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  // Set once login() reports AUTH_ACCOUNT_DEACTIVATED -- the password already checked out (see
  // AuthService.login()'s deactivated branch), so the rest of the form is replaced by a single
  // confirm step rather than making the user re-enter anything. Mirrors the web app's
  // reactivationToken state in Login.tsx / ReactivateAccountPrompt.tsx.
  const [reactivationToken, setReactivationToken] = useState<string | null>(null);
  const [reactivating, setReactivating] = useState(false);
  const [reactivateError, setReactivateError] = useState<string | null>(null);

  // A one-time confirmation passed by another screen (e.g. after a password reset). Read from
  // route params rather than held in state -- the Auth stack unmounts entirely once signed in,
  // so there's no stale-banner-on-revisit problem the web version had to clear history state for.
  const banner = route.params?.message ?? null;

  // Deliberately no format-restricting validation (unlike Register's email/phone fields) -- this
  // one field accepts either a full email address or a mobile number, so it can't be checked
  // against a single pattern. The backend resolves whichever form was typed (resolveEmailForLogin).
  const identifierValid = identifier.trim().length > 0;

  // See errorCodes.ts's own doc comment on AUTH_ACCOUNT_DEACTIVATED for why this compares
  // against a shared constant rather than a hand-typed literal here. `details` only reaches this
  // point because client.ts's response interceptor carries it through the error envelope.
  function handleAuthError(err: unknown, fallback: string) {
    const details = (err as { response?: { data?: { details?: { reactivationToken?: string } } } })?.response?.data
      ?.details;
    const token = apiErrorCode(err) === AUTH_ACCOUNT_DEACTIVATED ? details?.reactivationToken : null;
    if (token) {
      // Clears any error left over from a previous reactivation attempt -- this screen doesn't
      // unmount between attempts the way the web app's separate ReactivateAccountPrompt component
      // does, so a stale failure message would otherwise survive into this brand-new prompt.
      setReactivateError(null);
      setReactivationToken(token);
    } else {
      setError(toUserMessage(err, fallback));
    }
  }

  async function handleSubmit() {
    setError(null);
    if (!identifierValid) {
      setError('Enter your email or mobile number.');
      return;
    }
    if (password.length === 0) {
      setError('Enter your password.');
      return;
    }
    setLoading(true);
    try {
      // No navigation on success: RootNavigator swaps the whole Auth stack out once AuthContext
      // holds a token, and picks VerifyPhone vs. the app from phoneVerified. See its own comment.
      await login(identifier.trim(), password);
    } catch (err) {
      handleAuthError(err, 'Login failed. Check your credentials.');
    } finally {
      setLoading(false);
    }
  }

  async function handleReactivate() {
    if (!reactivationToken) return;
    setReactivating(true);
    setReactivateError(null);
    try {
      // No navigation on success, same reasoning as handleSubmit -- RootNavigator reacts to the
      // token AuthContext.reactivate() just persisted.
      await reactivate(reactivationToken);
    } catch (err) {
      // Most likely cause: the link expired (15 min) or was already used elsewhere -- either way,
      // the fix is the same one every other stale-token failure in this app uses: go back and try
      // again.
      setReactivateError(toUserMessage(err, 'Could not reactivate your account. Please try signing in again.'));
    } finally {
      setReactivating(false);
    }
  }

  if (reactivationToken) {
    return (
      <AuthScreenLayout title="Welcome back" error={reactivateError}>
        <Text style={[styles.body, { color: c.muted }]}>
          Your Finora account is deactivated. Sign in again to reactivate it — your data was
          retained and nothing was lost.
        </Text>

        <Button label="Reactivate my account" onPress={handleReactivate} loading={reactivating} />
        <View style={styles.cancelRow}>
          <Button
            label="Not you? Go back"
            variant="link"
            onPress={() => {
              setReactivationToken(null);
              setReactivateError(null);
            }}
            disabled={reactivating}
          />
        </View>

        <View style={[styles.notice, { backgroundColor: c.primaryLight }]}>
          <Text style={[styles.noticeText, { color: c.ink }]}>
            This link is valid for 15 minutes and can only be used once.
          </Text>
        </View>
      </AuthScreenLayout>
    );
  }

  return (
    <AuthScreenLayout
      title="Sign in"
      subtitle="Enter your details to access your account"
      error={error}
      banner={banner}
      footer={
        <View style={styles.footerRow}>
          <Text style={[styles.footerText, { color: c.muted }]}>No account? </Text>
          <Button label="Register" variant="link" onPress={() => navigation.navigate('Register')} />
        </View>
      }
    >
      <TextField
        label="Email or mobile number"
        value={identifier}
        onChangeText={setIdentifier}
        placeholder="you@example.com or +91XXXXXXXXXX"
        autoCapitalize="none"
        autoCorrect={false}
        autoComplete="username"
        keyboardType="email-address"
        returnKeyType="next"
      />

      <TextField
        label="Password"
        value={password}
        onChangeText={setPassword}
        secure
        autoCapitalize="none"
        autoComplete="current-password"
        returnKeyType="go"
        onSubmitEditing={handleSubmit}
      />

      <View style={styles.forgotRow}>
        <Button
          label="Forgot password?"
          variant="link"
          onPress={() => navigation.navigate('ForgotPassword')}
        />
      </View>

      <Button label="Sign in" onPress={handleSubmit} loading={loading} />

      <View style={[styles.notice, { backgroundColor: c.primaryLight }]}>
        <Text style={[styles.noticeText, { color: c.ink }]}>
          Your financial data is encrypted and securely protected.
        </Text>
      </View>
    </AuthScreenLayout>
  );
}

const styles = StyleSheet.create({
  body: {
    fontSize: 13,
    lineHeight: 20,
    marginBottom: spacing.md,
  },
  forgotRow: {
    alignSelf: 'flex-end',
    marginBottom: spacing.md,
  },
  cancelRow: {
    alignItems: 'center',
    marginTop: spacing.sm,
  },
  notice: {
    borderRadius: 8,
    padding: 12,
    marginTop: spacing.md,
  },
  noticeText: {
    fontSize: 12,
  },
  footerRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  footerText: {
    fontSize: 13,
  },
});
