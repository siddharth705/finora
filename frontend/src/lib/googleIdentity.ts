// D-23: "Sign in with Google" via Google Identity Services (GIS) — the button widget Google
// itself renders and controls, not a hand-rolled popup/redirect flow. The frontend never sees a
// password or an authorization code, only a signed credential (a JWT) that GoogleSignInButton
// hands to authApi.google(), which the backend verifies server-side
// (GoogleIdTokenVerifierService) before trusting anything in it.

export interface GoogleCredentialResponse {
  credential: string;
}

interface GoogleAccountsId {
  initialize(config: {
    client_id: string;
    callback: (response: GoogleCredentialResponse) => void;
  }): void;
  renderButton(
    parent: HTMLElement,
    options: {
      theme?: string; size?: string; width?: string | number; text?: string; shape?: string;
      logo_alignment?: 'left' | 'center';
    }
  ): void;
}

declare global {
  interface Window {
    google?: { accounts: { id: GoogleAccountsId } };
  }
}

const SCRIPT_SRC = 'https://accounts.google.com/gsi/client';

export function isGoogleLoginConfigured(): boolean {
  return Boolean(import.meta.env.VITE_GOOGLE_LOGIN_CLIENT_ID);
}

let scriptPromise: Promise<GoogleAccountsId> | null = null;

/**
 * Loads Google's own GIS script exactly once (cached across every call, including across
 * Register.tsx and Login.tsx both mounting it), and resolves once `window.google.accounts.id`
 * is actually usable. Rejects rather than hanging forever if the script fails to load (offline,
 * an ad blocker, Google's CDN unreachable) — GoogleSignInButton turns that into "button never
 * appears" rather than a silent dead click.
 */
export function loadGoogleIdentityServices(): Promise<GoogleAccountsId> {
  if (window.google?.accounts?.id) {
    return Promise.resolve(window.google.accounts.id);
  }
  if (!scriptPromise) {
    scriptPromise = new Promise<GoogleAccountsId>((resolve, reject) => {
      const existing = document.querySelector<HTMLScriptElement>(`script[src="${SCRIPT_SRC}"]`);
      const script = existing ?? document.createElement('script');
      script.addEventListener('load', () => {
        if (window.google?.accounts?.id) resolve(window.google.accounts.id);
        else reject(new Error('Google Identity Services loaded but window.google.accounts.id is missing.'));
      });
      script.addEventListener('error', () => reject(new Error('Failed to load Google Identity Services.')));
      if (!existing) {
        script.src = SCRIPT_SRC;
        script.async = true;
        script.defer = true;
        document.head.appendChild(script);
      }
    }).catch((err) => {
      // Don't cache a failure -- a later retry (e.g. the user's connection recovers, or they
      // navigate from Register to Login) should get a fresh attempt rather than being stuck
      // with the first failed load forever.
      scriptPromise = null;
      throw err;
    });
  }
  // Non-null: the branch above just assigned it when it was null.
  return scriptPromise!;
}
