import { RecaptchaVerifier, signInWithPhoneNumber, signOut, type ConfirmationResult } from 'firebase/auth';
import { getFirebaseAuth } from './firebase';

/**
 * Thin wrapper over Firebase Phone Authentication -- every page that needs OTP-gated phone
 * verification (VerifyPhone.tsx, ResetPassword.tsx, ChangePasswordModal.tsx) goes through these
 * two functions rather than touching the Firebase SDK directly, so the invisible-reCAPTCHA
 * lifecycle and the "sign out of Firebase once we have what we need" cleanup live in one place.
 *
 * Firebase is used transactionally here, not as this app's actual session mechanism -- Finora's
 * own JWT (see AuthContext) is the real session. confirmPhoneVerificationCode() signs out of
 * Firebase immediately after extracting the ID token, so there's nothing left lingering in
 * Firebase's own client-side auth state once a step completes.
 */

let recaptchaVerifier: RecaptchaVerifier | null = null;

// One verifier per container id, reused across calls on the same page rather than recreated --
// Firebase's own guidance is to create it once and render it once; recreating on every "send
// code" click (e.g. a retry) throws if the previous instance's underlying widget is still mounted.
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
 *  PhoneVerificationProvider), never the code itself. Throws (via Firebase's own error) for a
 *  wrong/expired code, same as any other Firebase Auth call. */
export async function confirmPhoneVerificationCode(confirmation: ConfirmationResult, code: string): Promise<string> {
  const credential = await confirmation.confirm(code);
  const idToken = await credential.user.getIdToken();
  await signOut(getFirebaseAuth()).catch(() => {});
  return idToken;
}

/** Call when leaving a page that rendered the invisible reCAPTCHA, so a later visit gets a fresh
 *  verifier instead of one bound to a container element that's since unmounted.
 *
 *  Also call this on every sendPhoneVerificationCode() failure, not only on unmount -- a consumed
 *  or expired invisible-reCAPTCHA widget throws auth/argument-error on reuse regardless of
 *  whether the retry's own inputs were fine, so a "Resend" click right after ANY failure silently
 *  fails for a reason that has nothing to do with what the user did. ChangePasswordModal.tsx's own
 *  fix for this is where this reasoning was first written down; VerifyPhone.tsx and
 *  ResetPassword.tsx had the same gap. Safe to call unconditionally -- a no-op on an
 *  already-null/unused verifier. */
export function resetPhoneVerification() {
  recaptchaVerifier?.clear();
  recaptchaVerifier = null;
}

/**
 * Maps a Firebase Auth error from {@link sendPhoneVerificationCode} to a message worth showing a
 * user -- one generic "could not send" string can't be told apart from any other failure, which
 * is exactly the problem when trying to learn which failure mode is actually occurring in
 * production. Covers the codes Firebase Phone Auth actually documents for this call, not invented
 * ones: abuse/rate protection, a malformed number, reCAPTCHA/app-verification failure, no network,
 * and the two shapes a genuine project misconfiguration takes.
 *
 * Deliberately does not attempt to distinguish "SMS quota exceeded" from "too many attempts from
 * this number" -- Firebase surfaces both as auth/too-many-requests, and guessing which one
 * occurred from the client would be inventing a distinction the SDK itself doesn't expose.
 */
export function friendlySendError(err: any): string {
  switch (err?.code) {
    case 'auth/too-many-requests':
      return 'Too many attempts. Please wait a few minutes before trying again.';
    case 'auth/quota-exceeded':
      return 'SMS delivery is temporarily unavailable. Please try again shortly.';
    case 'auth/invalid-phone-number':
      return "That doesn't look like a valid phone number.";
    case 'auth/captcha-check-failed':
    case 'auth/argument-error':
      return 'Verification could not start. Please refresh the page and try again.';
    case 'auth/network-request-failed':
      return 'Network error — check your connection and try again.';
    case 'auth/invalid-app-credential':
    case 'auth/operation-not-allowed':
      return 'Verification is temporarily unavailable. Please try again later.';
    default:
      return 'Could not send a verification code right now. Please try again.';
  }
}
