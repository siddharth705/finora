import { useMemo, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { AuthScreenLayout } from '../components/AuthScreenLayout';
import { Button } from '../components/Button';
import { TextField } from '../components/TextField';
import { useAuth } from '../context/AuthContext';
import { radius, spacing, useTheme } from '../theme';
import type { AuthStackParamList } from '../navigation/types';

type Props = NativeStackScreenProps<AuthStackParamList, 'Register'>;

// Every validation rule below is ported verbatim from frontend/src/pages/Register.tsx -- the two
// forms must agree on what's acceptable, and the backend enforces its own rules regardless.

// Simple, honest heuristic -- four independent signals, no external library. Purely a nudge,
// never a submission gate: the backend's 8-character minimum is the real requirement.
function passwordStrength(pw: string): { score: number; label: string } {
  let score = 0;
  if (pw.length >= 8) score++;
  if (/[a-z]/.test(pw) && /[A-Z]/.test(pw)) score++;
  if (/[0-9]/.test(pw)) score++;
  if (/[^A-Za-z0-9]/.test(pw)) score++;
  const labels = ['Too short', 'Weak', 'Fair', 'Good', 'Strong'];
  return { score, label: labels[score] };
}

// Digits only, capped at 10 -- the country code is a fixed "+91" prefix shown beside the field,
// never typed into it. Also handles a pasted full number ("+919876543210", "919876543210"): strip
// a leading "91" only when there'd otherwise be more than 10 digits, so a genuine 10-digit number
// starting 910-919 doesn't lose its first two digits.
function sanitizePhoneNumber(raw: string): string {
  const digitsOnly = raw.replace(/[^0-9]/g, '');
  const local = digitsOnly.length > 10 && digitsOnly.startsWith('91') ? digitsOnly.slice(2) : digitsOnly;
  return local.slice(0, 10);
}

// Real Indian mobile numbers always start 6-9.
const PHONE_PATTERN = /^[6-9][0-9]{9}$/;
// Letters (including accented/Unicode), spaces, hyphens, apostrophes, periods -- covers
// "Jean-Luc", "O'Brien", "Md. Rahman", "José" while rejecting digits and email-like input.
const FULL_NAME_PATTERN = /^[\p{L}][\p{L}\s.'-]{0,98}[\p{L}]$/u;
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export function RegisterScreen({ navigation }: Props) {
  const c = useTheme();
  const { register } = useAuth();
  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [phoneNumber, setPhoneNumber] = useState('');
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
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'Registration failed.');
    } finally {
      setLoading(false);
    }
  }

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

      <Button label="Create account" onPress={handleSubmit} loading={loading} disabled={!formValid} />

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
