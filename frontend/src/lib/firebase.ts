import { initializeApp, getApps, getApp } from 'firebase/app';
import { getAuth, type Auth } from 'firebase/auth';

// Phone verification (registration, password reset, authenticated password change) is Firebase
// Phone Authentication now -- the frontend sends and confirms the OTP directly against Firebase;
// the backend only ever sees the resulting ID token (see PhoneVerificationProvider on the backend
// for the other half of this). These six values come from Firebase Console -> Project Settings ->
// General -> "Your apps" -> the SDK config snippet -- not secrets in the way an API key to a
// paid/quota-limited service would be (Firebase's own docs treat this config as safe to ship in
// a client bundle; access control is enforced server-side via Firebase Security Rules / the
// Admin SDK, not by hiding this object), but still real per-project values that must come from
// your own Firebase project, not a placeholder.
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
 * Bug fix: this module used to call getAuth() (which throws immediately if VITE_FIREBASE_* is
 * unset -- "Firebase: Error (auth/invalid-api-key)") at MODULE LOAD time, via a top-level
 * `export const auth = getAuth(app)`. Because ChangePasswordModal (imported by Settings.tsx)
 * pulls this module in transitively, any environment without Firebase configured yet -- a fresh
 * local checkout before a Firebase project exists, CI, any deployment that hasn't set these env
 * vars -- crashed the moment Settings.tsx's own module graph was evaluated, not just the phone-
 * verification feature specifically. Firebase initialization is now deferred to first actual use
 * (getFirebaseAuth(), called from lib/phoneAuth.ts only when a verification flow actually runs),
 * matching the same "degrade gracefully when unconfigured, fail loudly only at the point of use"
 * pattern this codebase already applies server-side (NoOpEmailService, the old NoOpSmsService).
 */
export function getFirebaseAuth(): Auth {
  if (!isFirebaseConfigured()) {
    throw new Error(
      'Firebase is not configured (VITE_FIREBASE_API_KEY/AUTH_DOMAIN/PROJECT_ID/APP_ID are missing) -- phone verification is unavailable until these are set.'
    );
  }
  if (!cachedAuth) {
    // getApps()/getApp() guard against "Firebase App named '[DEFAULT]' already exists" -- this
    // can be called from multiple pages, and Vite/React's module graph doesn't guarantee a
    // fresh app instance per real page load (e.g. HMR in dev).
    const app = getApps().length ? getApp() : initializeApp(firebaseConfig);
    cachedAuth = getAuth(app);
  }
  return cachedAuth;
}
