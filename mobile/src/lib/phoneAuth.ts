import { getAuth, signInWithPhoneNumber, signOut } from '@react-native-firebase/auth';

// @react-native-firebase/auth doesn't re-export ConfirmationResult from its package root (it
// lives in an internal ./types/auth module with no public subpath export), so this derives the
// type from signInWithPhoneNumber's own return type instead of reaching into node_modules
// internals -- it stays correct automatically if the package's shape changes.
export type PhoneConfirmation = Awaited<ReturnType<typeof signInWithPhoneNumber>>;

/**
 * Native counterpart to the web app's frontend/src/lib/phoneAuth.ts. Same two-function contract,
 * same "Firebase is transactional, Finora's own JWT is the real session" model: the ID token is
 * extracted and Firebase is signed out immediately, so nothing lingers in Firebase's client-side
 * auth state.
 *
 * The one real difference from web: no reCAPTCHA. The web SDK requires an invisible-reCAPTCHA
 * verifier anchored to a DOM element (hence that file's getRecaptchaVerifier/resetPhoneVerification
 * lifecycle and VerifyPhone.tsx's <div id=...>); @react-native-firebase/auth performs app
 * verification natively instead -- silent APNs push on iOS, Play Integrity on Android -- so there
 * is no verifier to create, pass, or tear down. That also means no resetPhoneVerification()
 * equivalent is needed here.
 *
 * Note this uses the modular API (getAuth()/signInWithPhoneNumber(auth, ...)), not the deprecated
 * namespaced auth().signInWithPhoneNumber() form.
 */

/** Sends a verification code to phoneNumber (must be E.164, e.g. "+919876543210"). Returns
 *  Firebase's confirmation handle -- hold onto it and pass it to confirmPhoneVerificationCode()
 *  once the user types the code back in. */
export function sendPhoneVerificationCode(phoneNumber: string): Promise<PhoneConfirmation> {
  return signInWithPhoneNumber(getAuth(), phoneNumber);
}

/** Confirms the code against the handle from sendPhoneVerificationCode() and returns the resulting
 *  Firebase ID token -- this is what gets sent to the backend (see PhoneVerificationProvider),
 *  never the code itself. Throws (via Firebase's own error) for a wrong/expired code. */
export async function confirmPhoneVerificationCode(
  confirmation: PhoneConfirmation,
  code: string
): Promise<string> {
  const credential = await confirmation.confirm(code);
  if (!credential?.user) {
    throw new Error('Phone verification did not return a user credential.');
  }
  const idToken = await credential.user.getIdToken();
  await signOut(getAuth()).catch(() => {});
  return idToken;
}
