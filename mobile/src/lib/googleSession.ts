import { GoogleSignin } from '@react-native-google-signin/google-signin';

/**
 * Ends the Google Sign-In SDK's own session.
 *
 * The SDK caches the last account it signed in and reuses it silently: signIn() with a cached
 * account returns that account's id token without showing a picker. Two consequences, both real:
 * a device with more than one Google account is locked to whichever signed in first, and -- worse
 * -- Fynora's own sign-out leaves that credential in place, so the next person to press "Sign in
 * with Google" is handed the previous person's account.
 *
 * Called before every signIn() (so the picker always appears) and from clearLocalState() (so both
 * ways of ending a session end this one too). Failures are swallowed on purpose: signOut() rejects
 * when configure() has never run or nobody is signed in, which is the ordinary first-run state and
 * must never abort the sign-out or sign-in it precedes.
 */
export async function signOutOfGoogle(): Promise<void> {
  try {
    await GoogleSignin.signOut();
  } catch {
    // Nothing to sign out of, or Google was never configured on this build. Both are fine.
  }
}
