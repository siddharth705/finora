import { GoogleSignInButton } from './GoogleSignInButton';
import { isGoogleLoginConfigured } from '../lib/googleIdentity';

/**
 * The "prove you're still you" step for a Sign in with Google account, wherever a sensitive
 * action would otherwise ask for a current password -- ChangePasswordModal, DeleteAccountModal,
 * DeactivateAccountModal, ExportDataModal. A GOOGLE-method account's password is a random value
 * nobody, including the user, ever knows (see the backend User.signInMethod's own doc comment),
 * so a password field here can never be filled in correctly; this renders Google's own button
 * instead, and its credential (a fresh ID token, re-verified server-side by
 * GoogleReauthVerifier) is what proves control in place of a password.
 *
 * Bug fix (review): unlike Login.tsx/Register.tsx, this component has no password-field fallback
 * for the caller to degrade to -- a GOOGLE-method account genuinely has none. GoogleSignInButton
 * on its own renders nothing at all when Sign in with Google isn't configured for this
 * deployment (VITE_GOOGLE_LOGIN_CLIENT_ID unset), which would leave a GOOGLE-method user staring
 * at "verify your identity" with no interactive control anywhere underneath and no explanation --
 * a silent, total lockout in a misconfigured environment. Checked explicitly here instead.
 */
export function GoogleReauthPrompt({ onCredential, onError }: {
  onCredential: (idToken: string) => void | Promise<void>;
  onError: (message: string) => void;
}) {
  if (!isGoogleLoginConfigured()) {
    return (
      <p className="text-xs text-danger">
        Sign in with Google isn't available right now. Please try again later, or contact support.
      </p>
    );
  }
  return (
    <div>
      <p className="text-xs text-muted mb-2">
        This account signs in with Google. Verify your identity with Google to continue.
      </p>
      <GoogleSignInButton text="signin_with" onCredential={onCredential} onError={onError} />
    </div>
  );
}
