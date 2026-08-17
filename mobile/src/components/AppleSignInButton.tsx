import { useState } from 'react';
import { Platform, StyleSheet, View } from 'react-native';
import * as AppleAuthentication from 'expo-apple-authentication';
import { radius, useThemeSetting } from '../theme';

/**
 * D-26. iOS only, by design -- Apple's App Store Guideline 4.8 requires Sign In with Apple as an
 * equivalent option once an app offers a third-party login (Google), but ONLY on iOS; Android has
 * no equivalent rule, so it ships Google Sign-In alone (see the plan doc's D-26 entry). Renders
 * the library's own official AppleAuthenticationButton -- unlike Google's brand guidelines, Apple's
 * App Store review actually enforces this button's exact appearance, so this is the only
 * compliant choice, not just the simplest one.
 *
 * Unlike Google's ID token, Apple's carries no name claim at all -- fullName only ever arrives
 * from signInAsync() itself, and only on the user's very first authorization for this app. This
 * component is the one place that name is ever available; it's captured here and forwarded
 * through onCredential, never re-derived anywhere downstream (AppleIdentity, the backend's
 * verified identity, has no name field to fall back to).
 */
interface Props {
  buttonType?: AppleAuthentication.AppleAuthenticationButtonType;
  onCredential: (idToken: string, fullName?: string) => void | Promise<void>;
  onError: (message: string) => void;
}

export function AppleSignInButton({
  buttonType = AppleAuthentication.AppleAuthenticationButtonType.SIGN_IN,
  onCredential,
  onError,
}: Props) {
  const { resolved } = useThemeSetting();
  const [loading, setLoading] = useState(false);

  if (Platform.OS !== 'ios') return null;

  async function handlePress() {
    setLoading(true);
    try {
      const credential = await AppleAuthentication.signInAsync({
        requestedScopes: [
          AppleAuthentication.AppleAuthenticationScope.FULL_NAME,
          AppleAuthentication.AppleAuthenticationScope.EMAIL,
        ],
      });
      if (!credential.identityToken) {
        onError('Apple sign-in did not return a credential. Please try again.');
        return;
      }
      const fullName = credential.fullName
        ? AppleAuthentication.formatFullName(credential.fullName)
        : undefined;
      await onCredential(credential.identityToken, fullName || undefined);
    } catch (err) {
      const code = (err as { code?: unknown } | null)?.code;
      if (code === 'ERR_REQUEST_CANCELED') return; // user cancelled -- not an error state
      onError('Sign in with Apple is unavailable right now. Please try again later.');
    } finally {
      setLoading(false);
    }
  }

  return (
    <View style={[styles.wrap, loading && styles.loading]} pointerEvents={loading ? 'none' : 'auto'}>
      <AppleAuthentication.AppleAuthenticationButton
        buttonType={buttonType}
        buttonStyle={
          resolved === 'dark'
            ? AppleAuthentication.AppleAuthenticationButtonStyle.WHITE
            : AppleAuthentication.AppleAuthenticationButtonStyle.BLACK
        }
        cornerRadius={radius.md}
        style={styles.button}
        onPress={handlePress}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: {
    alignItems: 'center',
  },
  loading: {
    opacity: 0.5,
  },
  button: {
    width: '100%',
    height: 48,
  },
});
