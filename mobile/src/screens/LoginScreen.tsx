import { useState } from 'react';
import { Platform, StyleSheet, Text, View } from 'react-native';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { AppleSignInButton } from '../components/AppleSignInButton';
import { AuthScreenLayout } from '../components/AuthScreenLayout';
import { Button } from '../components/Button';
import { GoogleSignInButton, isGoogleSignInConfigured } from '../components/GoogleSignInButton';
import { TextField } from '../components/TextField';
import { useAuth } from '../context/AuthContext';
import { apiErrorCode, apiErrorDetails, toUserMessage } from '../lib/apiError';
import { AUTH_ACCOUNT_DEACTIVATED } from '../api/errorCodes';
import { spacing, useTheme } from '../theme';
import type { AuthStackParamList } from '../navigation/types';

type Props = NativeStackScreenProps<AuthStackParamList, 'Login'>;

export function LoginScreen({ navigation, route }: Props) {
  const c = useTheme();
  const { login, reactivate, loginWithGoogle, loginWithApple } = useAuth();
  // Phase 3B: AuthEntryScreen already resolved this identifier to an existing account via
  // POST /auth/identify -- prefill it here instead of asking the user to retype it. route.params
  // is stable for this screen instance (nothing here calls navigation.setParams), so it can be
  // read directly rather than captured once like web's Login.tsx has to (there, location.state
  // has to be snapshotted at mount since the same component instance persists across browser
  // history entries).
  //
  // Phase 7 (resolved 2026-08-23): this used to also read route.params.method and hide the
  // password form for a known GOOGLE/APPLE account -- removed along with nextAction no longer
  // revealing which method an account uses (see AuthEntryScreen's own doc comment). The password
  // field and Google/Apple buttons are always shown together now, matching a direct visit here.
  const [identifier, setIdentifier] = useState(route.params?.identifier ?? '');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  // Set once login() reports AUTH_ACCOUNT_DEACTIVATED -- the password already checked out (see
  // AuthService.login()'s deactivated branch), so the rest of the form is replaced by a single
  // confirm step rather than making the user re-enter anything. Mirrors the web app's
  // reactivationToken state in Login.tsx / ReactivateAccountPrompt.tsx. Its own loading/error
  // state reuses `loading`/`error` above rather than adding parallel state -- the reactivation
  // view below is a plain early return that never renders alongside the sign-in form, so the two
  // flows are never in progress, or showing a message, at the same time.
  const [reactivationToken, setReactivationToken] = useState<string | null>(null);

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
  //
  // No explicit `setError(null)` needed on the token branch: handleSubmit already clears `error`
  // before calling login(), and nothing sets it again before this runs -- so reusing `error` for
  // the reactivation view (see its own state comment) can't leak a stale plain-login failure into
  // a fresh reactivation prompt either.
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

  // Same "no navigation on success" reasoning as handleSubmit above -- RootNavigator swaps stacks
  // off AuthContext state, not an imperative call here.
  //
  // Routed through handleAuthError, not a raw setError: AuthService.loginWithOAuthIdentity calls
  // the exact same enforceAccountIsSignable gate login() does for an EXISTING account, so a
  // deactivated account signing in via Google/Apple gets the identical AUTH_ACCOUNT_DEACTIVATED
  // response, reactivationToken included -- reusing handleAuthError means that reactivation
  // prompt shows up here too, instead of a dead-end "Sign in with Google failed."
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

  // Apple's button has no client-side "configured" flag the way Google's does (webClientId) --
  // its backend counterpart degrades the same way Google's did pre-Railway-config (a real 503
  // surfaced through handleAppleCredential's catch), so it's always shown on iOS. The divider
  // only needs to know whether ANYTHING will render under it.
  const showSocialSignIn = isGoogleSignInConfigured() || Platform.OS === 'ios';

  async function handleReactivate() {
    if (!reactivationToken) return;
    setLoading(true);
    setError(null);
    try {
      // No navigation on success, same reasoning as handleSubmit -- RootNavigator reacts to the
      // token AuthContext.reactivate() just persisted.
      await reactivate(reactivationToken);
    } catch (err) {
      // Most likely cause: the link expired (15 min) or was already used elsewhere -- either way,
      // the fix is the same one every other stale-token failure in this app uses: go back and try
      // again.
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
              // Not covered by handleAuthError's redundancy argument -- a failed handleReactivate
              // sets `error` directly, with no equivalent of handleSubmit's leading clear on this
              // path, so it has to be reset here or it would leak into the sign-in form.
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

      <Button label="Sign in" onPress={handleSubmit} loading={loading} testID="login-submit" />

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
  footerRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  footerText: {
    fontSize: 13,
  },
});
