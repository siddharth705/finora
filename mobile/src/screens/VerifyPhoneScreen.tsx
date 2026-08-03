import { useEffect, useRef, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { AuthScreenLayout } from '../components/AuthScreenLayout';
import { Button } from '../components/Button';
import { TextField } from '../components/TextField';
import { phoneApi, userApi } from '../api/endpoints';
import { useAuth } from '../context/AuthContext';
import {
  confirmPhoneVerificationCode,
  sendPhoneVerificationCode,
  type PhoneConfirmation,
} from '../lib/phoneAuth';
import { maskPhone } from '../lib/maskPhone';
import { toUserMessage } from '../lib/apiError';
import { sanitizeOtp } from '../lib/validation';
import { spacing, useTheme } from '../theme';

/**
 * Ported from frontend/src/pages/VerifyPhone.tsx. The flow is identical apart from what the web
 * version needed for reCAPTCHA: no container id is passed to sendPhoneVerificationCode(), there's
 * no hidden anchor element to render, and there's no resetPhoneVerification() cleanup on unmount
 * -- @react-native-firebase/auth verifies the app natively instead (see src/lib/phoneAuth.ts).
 */
export function VerifyPhoneScreen() {
  const c = useTheme();
  const { setPhoneVerified, logout } = useAuth();
  const [otp, setOtp] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [phoneNumber, setPhoneNumber] = useState<string | null>(null);
  const [confirmation, setConfirmation] = useState<PhoneConfirmation | null>(null);
  const [loading, setLoading] = useState(false);
  const [sending, setSending] = useState(false);
  const startedRef = useRef(false);

  async function startVerification() {
    setSending(true);
    setError(null);
    setOtp('');
    try {
      // The account's real phone number is never carried through navigation params -- fetched
      // fresh here (this screen is only ever reached authenticated) and handed straight to
      // Firebase, which sends the code itself; this backend never does.
      const settings = await userApi.get();
      setPhoneNumber(settings.phoneNumber);
      const result = await sendPhoneVerificationCode(settings.phoneNumber);
      setConfirmation(result);
    } catch (err) {
      // Was a bare `catch {}` discarding the error, which made "you're offline", "Firebase isn't
      // configured in this build", and "rate limited" all render as the same sentence -- the three
      // cases with the most different fixes.
      setError(toUserMessage(err, 'Could not send a verification code right now.'));
    } finally {
      setSending(false);
    }
  }

  useEffect(() => {
    if (startedRef.current) return;
    startedRef.current = true;
    void startVerification();
  }, []);

  async function handleVerify() {
    if (!confirmation) return;
    setError(null);
    setLoading(true);
    try {
      const idToken = await confirmPhoneVerificationCode(confirmation, otp);
      await phoneApi.verify(idToken);
      // No navigation: flipping this flag is what moves RootNavigator to the app stack.
      setPhoneVerified(true);
    } catch (err) {
      // This try covers both Firebase (confirm) and the backend (phone/verify) -- toUserMessage
      // handles either family, which is why the local Firebase map is gone.
      setError(toUserMessage(err, 'Could not verify — try again.'));
    } finally {
      setLoading(false);
    }
  }

  return (
    <AuthScreenLayout
      title="Verify your phone"
      subtitle={`Enter the 6-digit code we sent to ${phoneNumber ? maskPhone(phoneNumber) : 'your mobile number'}.`}
      error={error}
      footer={
        // The web app reaches this screen mid-navigation and can always go back; here it's the
        // only screen in its stack (an unverified account can't reach anything else), so without
        // this there'd be no way out except uninstalling. Signing out returns to Login.
        <Button label="Sign out" variant="link" onPress={logout} />
      }
    >
      <TextField
        label="Verification code"
        value={otp}
        onChangeText={(v) => setOtp(sanitizeOtp(v))}
        placeholder="123456"
        keyboardType="number-pad"
        autoComplete="sms-otp"
        textContentType="oneTimeCode"
        // No maxLength, for the same reason as RegisterScreen's phone field: RN would apply it to
        // pasted text, so pasting a whole SMS ("Your code is 123456") would be cut to "Your c"
        // and then stripped to nothing. The onChangeText handler already digit-filters and caps.
        editable={!!confirmation}
        returnKeyType="go"
        onSubmitEditing={handleVerify}
      />

      <Button
        label="Verify"
        onPress={handleVerify}
        loading={loading}
        disabled={!confirmation || otp.length !== 6}
      />

      <View style={styles.resendRow}>
        <Button
          label={sending ? 'Sending…' : "Didn't get a code? Resend"}
          variant="link"
          onPress={startVerification}
          disabled={sending}
        />
      </View>

      <Text style={[styles.hint, { color: c.muted }]}>
        Verification is required before you can use your account.
      </Text>
    </AuthScreenLayout>
  );
}

const styles = StyleSheet.create({
  resendRow: {
    marginTop: spacing.md,
  },
  hint: {
    fontSize: 11,
    textAlign: 'center',
    marginTop: spacing.sm,
  },
});
