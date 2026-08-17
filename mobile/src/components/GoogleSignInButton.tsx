import { useState } from 'react';
import { StyleSheet, View } from 'react-native';
import {
  GoogleSignin,
  GoogleSigninButton,
  isErrorWithCode,
  isSuccessResponse,
  statusCodes,
} from '@react-native-google-signin/google-signin';
import { useThemeSetting } from '../theme';

/**
 * D-23 Phase 2. Native counterpart to frontend/src/components/GoogleSignInButton.tsx -- same
 * "unconfigured is a supported state, degrade silently" posture (see that component's own doc
 * comment), same onCredential/onError contract, but the sign-in mechanics are entirely different:
 * there's no Google Identity Services script to load, no DOM button to render into. This renders
 * the library's own official GoogleSigninButton (Google's brand guidelines require a specific
 * look; using their component is the simplest way to stay compliant, same reasoning as using
 * expo-apple-authentication's AppleAuthenticationButton for its sibling).
 *
 * GoogleSignin.configure() reads webClientId from EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID -- the OAuth
 * "Web client" id Google issues alongside the iOS/Android native ones, needed because that's the
 * client whose audience the BACKEND verifies against (GoogleIdTokenVerifierService), not the
 * platform-specific native client id. See mobile/.env.example.
 */
let configured = false;

function ensureConfigured(webClientId: string) {
  if (configured) return;
  GoogleSignin.configure({ webClientId });
  configured = true;
}

export function isGoogleSignInConfigured(): boolean {
  return Boolean(process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID);
}

interface Props {
  onCredential: (idToken: string) => void | Promise<void>;
  onError: (message: string) => void;
}

export function GoogleSignInButton({ onCredential, onError }: Props) {
  const { resolved } = useThemeSetting();
  const [loading, setLoading] = useState(false);

  const webClientId = process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID;
  if (!webClientId) return null;

  async function handlePress() {
    ensureConfigured(webClientId!);
    setLoading(true);
    try {
      // Android-only in practice (resolves true immediately on iOS) -- see the library's own
      // docs. Surfaces Play Services' own "update Play Services" dialog rather than a confusing
      // downstream failure when a device's copy is missing or out of date.
      await GoogleSignin.hasPlayServices({ showPlayServicesUpdateDialog: true });
      const response = await GoogleSignin.signIn();
      if (!isSuccessResponse(response)) return; // user cancelled -- not an error state
      if (!response.data.idToken) {
        onError('Google sign-in did not return a credential. Please try again.');
        return;
      }
      await onCredential(response.data.idToken);
    } catch (err) {
      if (isErrorWithCode(err) && err.code === statusCodes.SIGN_IN_CANCELLED) return;
      onError('Sign in with Google is unavailable right now. Please try again later.');
    } finally {
      setLoading(false);
    }
  }

  return (
    <View style={styles.wrap}>
      <GoogleSigninButton
        size={GoogleSigninButton.Size.Wide}
        color={resolved === 'dark' ? GoogleSigninButton.Color.Dark : GoogleSigninButton.Color.Light}
        onPress={handlePress}
        disabled={loading}
        style={styles.button}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: {
    alignItems: 'center',
  },
  button: {
    width: '100%',
    height: 48,
  },
});
