import { useState } from 'react';
import { Platform, StyleSheet, Text, View } from 'react-native';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { AppleSignInButton } from '../components/AppleSignInButton';
import { AuthScreenLayout } from '../components/AuthScreenLayout';
import { Button } from '../components/Button';
import { GoogleSignInButton, isGoogleSignInConfigured } from '../components/GoogleSignInButton';
import { TextField } from '../components/TextField';
import { useAuth } from '../context/AuthContext';
import { toUserMessage } from '../lib/apiError';
import { spacing, useTheme } from '../theme';
import type { AuthStackParamList } from '../navigation/types';

type Props = NativeStackScreenProps<AuthStackParamList, 'Login'>;

export function LoginScreen({ navigation, route }: Props) {
  const c = useTheme();
  const { login, loginWithGoogle, loginWithApple } = useAuth();
  const [identifier, setIdentifier] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  // A one-time confirmation passed by another screen (e.g. after a password reset). Read from
  // route params rather than held in state -- the Auth stack unmounts entirely once signed in,
  // so there's no stale-banner-on-revisit problem the web version had to clear history state for.
  const banner = route.params?.message ?? null;

  // Deliberately no format-restricting validation (unlike Register's email/phone fields) -- this
  // one field accepts either a full email address or a mobile number, so it can't be checked
  // against a single pattern. The backend resolves whichever form was typed (resolveEmailForLogin).
  const identifierValid = identifier.trim().length > 0;

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
      setError(toUserMessage(err, 'Login failed. Check your credentials.'));
    } finally {
      setLoading(false);
    }
  }

  // Same "no navigation on success" reasoning as handleSubmit above -- RootNavigator swaps stacks
  // off AuthContext state, not an imperative call here.
  async function handleGoogleCredential(idToken: string) {
    setError(null);
    try {
      await loginWithGoogle(idToken);
    } catch (err) {
      setError(toUserMessage(err, 'Sign in with Google failed.'));
    }
  }

  async function handleAppleCredential(idToken: string, fullName?: string) {
    setError(null);
    try {
      await loginWithApple(idToken, fullName);
    } catch (err) {
      setError(toUserMessage(err, 'Sign in with Apple failed.'));
    }
  }

  // Apple's button has no client-side "configured" flag the way Google's does (webClientId) --
  // its backend counterpart degrades the same way Google's did pre-Railway-config (a real 503
  // surfaced through handleAppleCredential's catch), so it's always shown on iOS. The divider
  // only needs to know whether ANYTHING will render under it.
  const showSocialSignIn = isGoogleSignInConfigured() || Platform.OS === 'ios';

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

      {showSocialSignIn ? (
        <>
          <View style={styles.dividerRow}>
            <View style={[styles.dividerLine, { backgroundColor: c.border }]} />
            <Text style={[styles.dividerText, { color: c.muted }]}>OR</Text>
            <View style={[styles.dividerLine, { backgroundColor: c.border }]} />
          </View>
          <View style={styles.socialStack}>
            <GoogleSignInButton onCredential={handleGoogleCredential} onError={setError} />
            <AppleSignInButton onCredential={handleAppleCredential} onError={setError} />
          </View>
        </>
      ) : null}

      <View style={[styles.notice, { backgroundColor: c.primaryLight }]}>
        <Text style={[styles.noticeText, { color: c.ink }]}>
          Your financial data is encrypted and securely protected.
        </Text>
      </View>
    </AuthScreenLayout>
  );
}

const styles = StyleSheet.create({
  forgotRow: {
    alignSelf: 'flex-end',
    marginBottom: spacing.md,
  },
  dividerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginTop: spacing.md,
    marginBottom: spacing.sm,
    gap: spacing.sm,
  },
  dividerLine: {
    flex: 1,
    height: 1,
  },
  dividerText: {
    fontSize: 11,
    fontWeight: '600',
  },
  socialStack: {
    gap: spacing.sm,
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
