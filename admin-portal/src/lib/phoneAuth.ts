import { RecaptchaVerifier, signInWithPhoneNumber, signOut, type ConfirmationResult } from 'firebase/auth';
import { getFirebaseAuth } from './firebase';

/**
 * Thin wrapper over Firebase Phone Authentication -- identical in shape to frontend/src/lib/
 * phoneAuth.ts (VerifyPhone.tsx and ResetPassword.tsx here go through these two functions rather
 * than touching the Firebase SDK directly). Firebase is used transactionally, not as this app's
 * session mechanism -- Finora's own JWT (see AdminAuthContext) is the real session.
 */

let recaptchaVerifier: RecaptchaVerifier | null = null;

function getRecaptchaVerifier(containerId: string): RecaptchaVerifier {
  if (!recaptchaVerifier) {
    recaptchaVerifier = new RecaptchaVerifier(getFirebaseAuth(), containerId, { size: 'invisible' });
  }
  return recaptchaVerifier;
}

/** Sends a verification code to phoneNumber (must be E.164, e.g. "+919876543210") via an
 *  invisible reCAPTCHA anchored at the DOM element with id=containerId. Returns Firebase's own
 *  ConfirmationResult -- hold onto it and pass it to confirmPhoneVerificationCode() once the user
 *  types the code back in. Throws if Firebase isn't configured (see getFirebaseAuth()). */
export function sendPhoneVerificationCode(phoneNumber: string, containerId: string): Promise<ConfirmationResult> {
  const verifier = getRecaptchaVerifier(containerId);
  return signInWithPhoneNumber(getFirebaseAuth(), phoneNumber, verifier);
}

/** Confirms the code against the ConfirmationResult from sendPhoneVerificationCode(), and returns
 *  the resulting Firebase ID token -- this is what gets sent to the backend (see
 *  PhoneVerificationProvider), never the code itself. */
export async function confirmPhoneVerificationCode(confirmation: ConfirmationResult, code: string): Promise<string> {
  const credential = await confirmation.confirm(code);
  const idToken = await credential.user.getIdToken();
  await signOut(getFirebaseAuth()).catch(() => {});
  return idToken;
}

/** Call when leaving a page that rendered the invisible reCAPTCHA, so a later visit gets a fresh
 *  verifier instead of one bound to a container element that's since unmounted. */
export function resetPhoneVerification() {
  recaptchaVerifier?.clear();
  recaptchaVerifier = null;
}
