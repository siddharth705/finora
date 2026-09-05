import { useState } from 'react';
import { Platform, StyleSheet, Text, View } from 'react-native';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { AppleSignInButton } from '../components/AppleSignInButton';
import { AuthScreenLayout } from '../components/AuthScreenLayout';
import { Button } from '../components/Button';
import { GoogleSignInButton, isGoogleSignInConfigured } from '../components/GoogleSignInButton';
import { TextField } from '../components/TextField';
import { useAuth } from '../context/AuthContext';
import { authApi } from '../api/endpoints';
import { apiErrorCode, apiErrorDetails, toUserMessage } from '../lib/apiError';
import { AUTH_ACCOUNT_DEACTIVATED } from '../api/errorCodes';
import { EMAIL_PATTERN } from '../lib/validation';
import { spacing, useTheme } from '../theme';
import type { AuthStackParamList } from '../navigation/types';

type Props = NativeStackScreenProps<AuthStackParamList, 'AuthEntry'>;

/**
 * Phase 3B: the unified identifier-first entry screen, mirroring web's AuthEntry.tsx (Phase 3A).
 * A single "email or mobile number" field replaces having to pick Login vs Register up front --
 * POST /auth/identify resolves it to what should happen next, and this screen navigates there:
 *
 * - EXISTS -- an account exists for this identifier (any sign-in method). Sent to Login with the
 *   identifier prefilled; the password field and Google/Apple buttons are always shown together
 *   there, same as a direct visit -- see Phase 7's amendment below for why this no longer
 *   branches on which method the account actually uses.
 * - CONTINUE -- no account behind this identifier. Sent to Register with whichever of its two
 *   fields (email or mobile number) the identifier looks like, prefilled.
 *
 * Login and Register stay fully reachable on their own via this screen's footer AND their own
 * "No account? Register" / "Already have an account? Sign in" links -- this screen fronts them,
 * it doesn't gate them.
 *
 * Phase 7 amendment (resolved 2026-08-23): nextAction used to be PASSWORD/GOOGLE/APPLE/CONTINUE,
 * and this screen forwarded the method to LoginScreen so it could hide the password field for a
 * known OAuth account (§2.4's "move the OAuth-user rejection earlier"). Collapsed to EXISTS/
 * CONTINUE to stop /auth/identify revealing which sign-in method an existing account uses -- see
 * IdentifyResponse's own doc comment on the backend for the full reasoning. The backend's own
 * signInMethod refusal at actual login time is unaffected.
 */
export function AuthEntryScreen({ navigation }: Props) {
  const c = useTheme();
  const { loginWithGoogle, loginWithApple, reactivate } = useAuth();
  const [identifier, setIdentifier] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  // Mirrors LoginScreen's own reactivation flow -- reachable here too, since the "Continue with
  // Google/Apple" buttons below can hit a deactivated account directly, bypassing /auth/identify
  // (and Login's own copy of this flow) entirely.
  const [reactivationToken, setReactivationToken] = useState<string | null>(null);

  const identifierValid = identifier.trim().length > 0;

  function handleAuthError(err: unknown, fallback: string) {
    const details = apiErrorDetails<{ reactivationToken?: string }>(err);
    const token = apiErrorCode(err) === AUTH_ACCOUNT_DEACTIVATED ? details?.reactivationToken : null;
    if (token) {
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
    setLoading(true);
    try {
      const trimmed = identifier.trim();
      const { nextAction } = await authApi.identify(trimmed);
      if (nextAction === 'CONTINUE') {
        const isEmail = EMAIL_PATTERN.test(trimmed);
        navigation.navigate('Register', isEmail ? { email: trimmed } : { phoneNumber: trimmed });
      } else {
        navigation.navigate('Login', { identifier: trimmed });
      }
    } catch (err) {
      setError(toUserMessage(err, 'Something went wrong. Please try again.'));
    } finally {
      setLoading(false);
    }
  }

  async function handleGoogleCredential(idToken: string) {
    setError(null);
    try {
      await loginWithGoogle(idToken);
    } catch (err) {
      handleAuthError(err, 'Sign in with Google failed.');
    }
  }

  async function handleAppleCredential(idToken: string, fullName?: string) {
    setError(null);
    try {
      await loginWithApple(idToken, fullName);
    } catch (err) {
      handleAuthError(err, 'Sign in with Apple failed.');
    }
  }

  // See LoginScreen's own comment on why this doesn't need an Apple-specific "configured" check.
  const showSocialSignIn = isGoogleSignInConfigured() || Platform.OS === 'ios';

  async function handleReactivate() {
    if (!reactivationToken) return;
    setLoading(true);
    setError(null);
    try {
      await reactivate(reactivationToken);
    } catch (err) {
      setError(toUserMessage(err, 'Could not reactivate your account. Please try signing in again.'));
    } finally {
      setLoading(false);
    }
  }

  if (reactivationToken) {
    return (
      <AuthScreenLayout title="Welcome back" error={error}>
        <Text style={[styles.body, { color: c.muted }]}>
          Your Fynora account is deactivated. Sign in again to reactivate it — your data was
          retained and nothing was lost.
        </Text>

        <Button label="Reactivate my account" onPress={handleReactivate} loading={loading} />
        <View style={styles.cancelRow}>
          <Button
            label="Not you? Go back"
            variant="link"
            onPress={() => {
              setReactivationToken(null);
              setError(null);
            }}
            disabled={loading}
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
      title="Sign in or create an account"
      subtitle="Enter your email or mobile number to continue"
      error={error}
    >
      {showSocialSignIn ? (
        <>
          <View style={styles.socialStack}>
            <GoogleSignInButton onCredential={handleGoogleCredential} onError={setError} />
            <AppleSignInButton onCredential={handleAppleCredential} onError={setError} />
          </View>
          <View style={styles.dividerRow}>
            <View style={[styles.dividerLine, { backgroundColor: c.border }]} />
            <Text style={[styles.dividerText, { color: c.muted }]}>Or continue below</Text>
            <View style={[styles.dividerLine, { backgroundColor: c.border }]} />
          </View>
        </>
      ) : null}

      <TextField
        label="Email or mobile number"
        value={identifier}
        onChangeText={setIdentifier}
        placeholder="you@example.com or +91XXXXXXXXXX"
        autoCapitalize="none"
        autoCorrect={false}
        autoComplete="username"
        keyboardType="email-address"
        returnKeyType="go"
        onSubmitEditing={handleSubmit}
      />

      <Button label="Continue" onPress={handleSubmit} loading={loading} />

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
    // Applied here rather than typed in caps: VoiceOver/TalkBack often spell out a long
    // hardcoded-caps phrase letter by letter, mistaking it for an acronym -- this keeps the
    // underlying text natural-case (readable as words) while still rendering all-caps visually,
    // same split the web AuthDivider already gets from CSS text-transform.
    textTransform: 'uppercase',
  },
  socialStack: {
    gap: spacing.sm,
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
});
