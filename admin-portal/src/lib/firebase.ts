import { initializeApp, getApps, getApp } from 'firebase/app';
import { getAuth, type Auth } from 'firebase/auth';

// Same Firebase project/config the user app (frontend/) uses -- phone verification (an admin-
// created account's first sign-in, and admin password reset) is Firebase Phone Authentication
// here too, for the same reasoning as frontend/src/lib/firebase.ts. These six values come from
// Firebase Console -> Project Settings -> General -> "Your apps" -> the SDK config snippet.
const firebaseConfig = {
  apiKey: import.meta.env.VITE_FIREBASE_API_KEY,
  authDomain: import.meta.env.VITE_FIREBASE_AUTH_DOMAIN,
  projectId: import.meta.env.VITE_FIREBASE_PROJECT_ID,
  storageBucket: import.meta.env.VITE_FIREBASE_STORAGE_BUCKET,
  messagingSenderId: import.meta.env.VITE_FIREBASE_MESSAGING_SENDER_ID,
  appId: import.meta.env.VITE_FIREBASE_APP_ID,
};

export function isFirebaseConfigured(): boolean {
  return Boolean(firebaseConfig.apiKey && firebaseConfig.authDomain && firebaseConfig.projectId && firebaseConfig.appId);
}

let cachedAuth: Auth | null = null;

/**
 * Lazy, cached Firebase init -- deferred to first actual use (called from lib/phoneAuth.ts only
 * when a verification flow actually runs), not at module load time. Same bug this pattern avoids
 * in frontend/src/lib/firebase.ts: a top-level getAuth() call would crash every page that
 * transitively imports this module (e.g. via VerifyPhone.tsx) in any environment without
 * VITE_FIREBASE_* set, not just the phone-verification screens themselves.
 */
export function getFirebaseAuth(): Auth {
  if (!isFirebaseConfigured()) {
    throw new Error(
      'Firebase is not configured (VITE_FIREBASE_API_KEY/AUTH_DOMAIN/PROJECT_ID/APP_ID are missing) -- phone verification is unavailable until these are set.'
    );
  }
  if (!cachedAuth) {
    const app = getApps().length ? getApp() : initializeApp(firebaseConfig);
    cachedAuth = getAuth(app);
  }
  return cachedAuth;
}
