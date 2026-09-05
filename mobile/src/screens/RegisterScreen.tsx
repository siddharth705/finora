import { useMemo, useState } from 'react';
import { Platform, StyleSheet, Text, View } from 'react-native';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import * as AppleAuthentication from 'expo-apple-authentication';
import { AppleSignInButton } from '../components/AppleSignInButton';
import { AuthScreenLayout } from '../components/AuthScreenLayout';
import { Button } from '../components/Button';
import { GoogleSignInButton, isGoogleSignInConfigured } from '../components/GoogleSignInButton';
import { TextField } from '../components/TextField';
import { useAuth } from '../context/AuthContext';
import { toUserMessage } from '../lib/apiError';
import {
  EMAIL_PATTERN, FULL_NAME_PATTERN, PHONE_PATTERN, passwordStrength, sanitizePhoneNumber,
} from '../lib/validation';
import { radius, spacing, useTheme } from '../theme';
import type { AuthStackParamList } from '../navigation/types';

type Props = NativeStackScreenProps<AuthStackParamList, 'Register'>;

// Validation rules live in lib/validation.ts so they're directly testable and shared with the
// account forms Phases 4-5 add. They must agree with frontend/src/pages/Register.tsx.

export function RegisterScreen({ navigation, route }: Props) {
  const c = useTheme();
  const { register, loginWithGoogle, loginWithApple } = useAuth();
  // Phase 3B: AuthEntryScreen already learned (via POST /auth/identify) that this identifier has
  // no account yet, and knew which of these two fields it looked like -- prefilled here so the
  // user doesn't have to retype what they already entered. phoneNumber is run through the same
  // sanitizer the field's own onChangeText uses, since AuthEntry hands over the full identifier
  // (e.g. "+919876543210" -- synthetic-ok: same fake sequential example number used elsewhere in
  // this file), not the local-only 10 digits this field stores.
  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState(route.params?.email ?? '');
  const [phoneNumber, setPhoneNumber] = useState(
    route.params?.phoneNumber ? sanitizePhoneNumber(route.params.phoneNumber) : '',
  );
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  // Only surface field-level errors once a field has been left, so the empty form doesn't mount
  // covered in red.
  const [touched, setTouched] = useState<Record<string, boolean>>({});

  const trimmedName = fullName.trim();
  const fullNameValid = trimmedName.length >= 2 && FULL_NAME_PATTERN.test(trimmedName);
  const emailValid = EMAIL_PATTERN.test(email.trim());
  const phoneValid = PHONE_PATTERN.test(phoneNumber);
  const passwordLongEnough = password.length >= 8;
  const strength = useMemo(() => passwordStrength(password), [password]);
  const passwordsMatch = confirmPassword.length > 0 && confirmPassword === password;

  const formValid = fullNameValid && emailValid && phoneValid && passwordLongEnough && passwordsMatch;

  function markTouched(field: string) {
    setTouched((t) => ({ ...t, [field]: true }));
  }

  async function handleSubmit() {
    setError(null);
    setTouched({ fullName: true, email: true, phoneNumber: true, password: true, confirmPassword: true });

    if (!fullNameValid) { setError('Enter your full name using letters, spaces, hyphens, or apostrophes only.'); return; }
    if (!emailValid) { setError('Enter a valid email address.'); return; }
    if (!phoneValid) { setError('Enter a valid 10-digit mobile number.'); return; }
    if (!passwordLongEnough) { setError('Password must be at least 8 characters.'); return; }
    if (!passwordsMatch) { setError('Passwords do not match.'); return; }

    setLoading(true);
    try {
      // Trimmed at the submission boundary so the account is never created with stray whitespace
      // in the name or email. +91 is prepended here, once -- it's never held in state.
      await register(email.trim(), password, trimmedName, `+91${phoneNumber}`);
      // No navigation: RootNavigator switches stacks off AuthContext state, landing on
      // VerifyPhone since a fresh registration is never phone-verified yet.
    } catch (err) {
      setError(toUserMessage(err, 'Registration failed.'));
    } finally {
      setLoading(false);
    }
  }

  // No navigation on success, same reasoning as handleSubmit above.
  async function handleGoogleCredential(idToken: string) {
    setError(null);
    try {
      await loginWithGoogle(idToken);
    } catch (err) {
      setError(toUserMessage(err, 'Sign up with Google failed.'));
    }
  }

  async function handleAppleCredential(idToken: string, fullName?: string) {
    setError(null);
    try {
      await loginWithApple(idToken, fullName);
    } catch (err) {
      setError(toUserMessage(err, 'Sign up with Apple failed.'));
    }
  }

  // See LoginScreen's own comment on why this doesn't need an Apple-specific "configured" check.
  const showSocialSignIn = isGoogleSignInConfigured() || Platform.OS === 'ios';

  return (
    <AuthScreenLayout
      title="Create your account"
      subtitle="Start your journey towards financial clarity"
      error={error}
      footer={
        <View style={styles.footerRow}>
          <Text style={[styles.footerText, { color: c.muted }]}>Already have an account? </Text>
          <Button label="Sign in" variant="link" onPress={() => navigation.navigate('Login')} />
        </View>
      }
    >
      {showSocialSignIn ? (
        <>
          <View style={styles.socialStack}>
            <GoogleSignInButton onCredential={handleGoogleCredential} onError={setError} />
            <AppleSignInButton
              buttonType={AppleAuthentication.AppleAuthenticationButtonType.SIGN_UP}
              onCredential={handleAppleCredential}
              onError={setError}
            />
          </View>
          <View style={styles.dividerRow}>
            <View style={[styles.dividerLine, { backgroundColor: c.border }]} />
            <Text style={[styles.dividerText, { color: c.muted }]}>Or continue below</Text>
            <View style={[styles.dividerLine, { backgroundColor: c.border }]} />
          </View>
        </>
      ) : null}

      <TextField
        label="Full name"
        value={fullName}
        onChangeText={setFullName}
        onBlur={() => markTouched('fullName')}
        placeholder="Enter your full name"
        autoCapitalize="words"
        autoComplete="name"
        error={touched.fullName && !fullNameValid ? 'Letters, spaces, hyphens, and apostrophes only.' : null}
      />

      <TextField
        label="Email"
        value={email}
        onChangeText={setEmail}
        onBlur={() => markTouched('email')}
        placeholder="you@example.com"
        autoCapitalize="none"
        autoCorrect={false}
        autoComplete="email"
        keyboardType="email-address"
        error={touched.email && !emailValid ? 'Enter a valid email address.' : null}
      />

      <TextField
        label="Mobile number"
        value={phoneNumber}
        onChangeText={(v) => setPhoneNumber(sanitizePhoneNumber(v))}
        onBlur={() => markTouched('phoneNumber')}
        placeholder="XXXXXXXXXX"
        prefix="+91"
        keyboardType="number-pad"
        autoComplete="tel"
        // Deliberately no maxLength: RN applies it to pasted text too, which would truncate
        // "+919876543210" to "+91987654" BEFORE sanitizePhoneNumber could strip the country code,
        // turning a correctly-pasted number into a wrong one. (The web form avoids this by
        // reading the clipboard in a separate onPaste handler, which RN has no equivalent for.)
        // sanitizePhoneNumber already caps at 10 digits, so typing is still bounded.
        error={touched.phoneNumber && !phoneValid ? 'Enter a valid 10-digit mobile number (no leading 0-5).' : null}
      />

      <TextField
        label="Password (min 8 characters)"
        value={password}
        onChangeText={setPassword}
        onBlur={() => markTouched('password')}
        secure
        autoCapitalize="none"
        autoComplete="new-password"
        maxLength={72}
        error={touched.password && password.length === 0 ? 'Password is required.' : null}
      />
      {password.length > 0 ? (
        <View style={styles.strengthWrap}>
          <View style={styles.strengthBars}>
            {[0, 1, 2, 3].map((i) => (
              <View
                key={i}
                style={[
                  styles.strengthBar,
                  { backgroundColor: i < strength.score ? c.primary : c.border },
                ]}
              />
            ))}
          </View>
          <Text style={[styles.strengthLabel, { color: c.muted }]}>{strength.label}</Text>
        </View>
      ) : null}

      <TextField
        label="Confirm password"
        value={confirmPassword}
        onChangeText={setConfirmPassword}
        onBlur={() => markTouched('confirmPassword')}
        secure
        autoCapitalize="none"
        autoComplete="new-password"
        error={touched.confirmPassword && !passwordsMatch ? "Passwords don't match." : null}
      />

      <Button label="Create account" onPress={handleSubmit} loading={loading} disabled={!formValid} pressScale />

      {/* The web form gates submission on an explicit Terms & Privacy checkbox. Those pages are
          marketing routes that aren't part of the mobile app, so there's nothing to link to yet;
          the checkbox is deliberately omitted rather than shown pointing nowhere. Restore it
          alongside in-app Terms/Privacy screens (see the roadmap's store-readiness phase, where
          both stores require a reachable privacy policy anyway). */}
      <View style={[styles.notice, { backgroundColor: c.primaryLight }]}>
        <Text style={[styles.noticeText, { color: c.ink }]}>
          Your financial data is encrypted and securely protected.
        </Text>
      </View>
    </AuthScreenLayout>
  );
}

const styles = StyleSheet.create({
  strengthWrap: {
    marginBottom: spacing.sm,
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
  strengthBars: {
    flexDirection: 'row',
    gap: 4,
    marginBottom: 4,
  },
  strengthBar: {
    height: 4,
    flex: 1,
    borderRadius: 2,
  },
  strengthLabel: {
    fontSize: 11,
  },
  notice: {
    borderRadius: radius.md,
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
