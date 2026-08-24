// D-26 (web). Same shape as D-23's googleIdentity.ts -- "Sign in with Apple JS" hands back a
// signed ID token directly (via the promise AppleID.auth.signIn() resolves), verified server-side
// (AppleIdTokenVerifierService) before anything in it is trusted. Popup mode (usePopup: true) was
// chosen over a full-page redirect to match Google's one-click posture; a redirectURI is still
// required by Apple's API even in popup mode, used internally as the postMessage-relay target.

interface AppleCredentialResponse {
  authorization: {
    id_token: string;
    code: string;
    state?: string;
  };
  // Only ever present on the FIRST authorization for a given Apple ID / client id pair -- Apple
  // does not resend it on subsequent sign-ins, same constraint the native flow already has.
  user?: {
    name?: { firstName?: string; lastName?: string };
    email?: string;
  };
}

interface AppleAuth {
  init(config: {
    clientId: string;
    scope?: string;
    redirectURI: string;
    usePopup?: boolean;
  }): void;
  signIn(): Promise<AppleCredentialResponse>;
}

declare global {
  interface Window {
    AppleID?: { auth: AppleAuth };
  }
}

const SCRIPT_SRC = 'https://appleid.cdn-apple.com/appleauth/static/jsapi/appleid/1/en_US/appleid.auth.js';

export function isAppleLoginConfigured(): boolean {
  return (
    Boolean(import.meta.env.VITE_APPLE_LOGIN_CLIENT_ID) &&
    Boolean(import.meta.env.VITE_APPLE_LOGIN_REDIRECT_URI)
  );
}

let scriptPromise: Promise<AppleAuth> | null = null;

/**
 * Loads Apple's own Sign in with Apple JS script exactly once (cached across every call,
 * including across Register.tsx and Login.tsx both mounting it), and resolves once
 * `window.AppleID.auth` is actually usable. Rejects rather than hanging forever if the script
 * fails to load -- AppleSignInButton turns that into "button reports an error" rather than a
 * silent dead click.
 */
export function loadAppleIdServices(): Promise<AppleAuth> {
  if (window.AppleID?.auth) {
    return Promise.resolve(window.AppleID.auth);
  }
  if (!scriptPromise) {
    scriptPromise = new Promise<AppleAuth>((resolve, reject) => {
      const existing = document.querySelector<HTMLScriptElement>(`script[src="${SCRIPT_SRC}"]`);
      const script = existing ?? document.createElement('script');
      script.addEventListener('load', () => {
        if (window.AppleID?.auth) resolve(window.AppleID.auth);
        else reject(new Error('Apple ID JS loaded but window.AppleID.auth is missing.'));
      });
      script.addEventListener('error', () => reject(new Error('Failed to load Sign in with Apple JS.')));
      if (!existing) {
        script.src = SCRIPT_SRC;
        script.async = true;
        script.defer = true;
        document.head.appendChild(script);
      }
    }).catch((err) => {
      // Don't cache a failure -- a later retry should get a fresh attempt rather than being
      // stuck with the first failed load forever.
      scriptPromise = null;
      throw err;
    });
  }
  return scriptPromise!;
}
