import { useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { AuthScreenLayout } from '../components/AuthScreenLayout';
import { Button } from '../components/Button';
import { TextField } from '../components/TextField';
import { useAuth } from '../context/AuthContext';
import { toUserMessage } from '../lib/apiError';
import { spacing, useTheme } from '../theme';
import type { AuthStackParamList } from '../navigation/types';

type Props = NativeStackScreenProps<AuthStackParamList, 'Login'>;

export function LoginScreen({ navigation, route }: Props) {
  const c = useTheme();
  const { login } = useAuth();
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
  forgotRow: {
    alignSelf: 'flex-end',
    marginBottom: spacing.md,
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
