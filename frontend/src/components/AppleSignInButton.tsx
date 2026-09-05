import { useEffect, useState } from 'react';
import { isAppleLoginConfigured, loadAppleIdServices } from '../lib/appleIdentity';

interface AppleSignInButtonProps {
  // fullName is null whenever Apple omits the `user` object -- every sign-in after the account's
  // very first authorization for this client id, same constraint the native flow already has.
  onCredential: (idToken: string, fullName: string | null) => void | Promise<void>;
  onError: (message: string) => void;
}

function initAppleAuth(auth: Awaited<ReturnType<typeof loadAppleIdServices>>) {
  auth.init({
    clientId: import.meta.env.VITE_APPLE_LOGIN_CLIENT_ID!,
    scope: 'name email',
    redirectURI: import.meta.env.VITE_APPLE_LOGIN_REDIRECT_URI!,
    usePopup: true,
  });
}

/**
 * True only when this page was opened BY another window and isn't that window itself -- the
 * ordinary way to load /auth (typing the URL, following a link) never satisfies this. The one
 * realistic way it does is being the usePopup redirect target this component's own eager-load
 * effect exists for, which is exactly the context where an eager-load failure has nobody around
 * to see the button's own onError (see that effect's own comment).
 */
function isLikelyOAuthPopup(): boolean {
  try {
    return Boolean(window.opener) && window.opener !== window;
  } catch {
    // Cross-origin window.opener access throws in some browsers rather than returning null.
    return false;
  }
}

/**
 * D-26 (web). A custom button that calls AppleID.auth.signIn() directly rather than Apple's own
 * auto-scanned `id="appleid-signin"` div -- that div is only reliably picked up by elements
 * present in the DOM at the moment the script first scans it, which React's mount timing can't
 * guarantee. Calling signIn() from a click handler is Apple's own documented alternative and
 * sidesteps that race entirely.
 *
 * Renders nothing when unconfigured -- same "unconfigured is a supported state, degrade silently"
 * posture as GoogleSignInButton.tsx, until the real Apple Developer Portal Services ID exists.
 *
 * Also loads and initializes the SDK eagerly on mount, not only on click. usePopup mode's redirect
 * URI (VITE_APPLE_LOGIN_REDIRECT_URI) points at this same /auth route: when Apple's authorization
 * server completes the flow, it POSTs to that URI *inside the popup window*, which is a fresh page
 * load of this same SPA -- nobody clicks a button there. The SDK's own popup-to-opener relay logic
 * only runs if `appleid.auth.js` is actually loaded and initialized on that page, so waiting for a
 * click (which never happens in the popup) would leave the relay stuck. The click handler below
 * still loads/inits again before calling signIn() -- both loadAppleIdServices() and auth.init()
 * are idempotent, so this is just a safety net if the effect's promise hasn't resolved yet.
 *
 * An eager-load failure is silent on a normal page load (the click handler's own retry reports
 * through onError if the user actually tries) but NOT inside the popup: nobody there is going to
 * click anything, so a failure there would otherwise never be seen by anyone -- the popup would
 * just sit on the ordinary sign-in form, doing nothing, with no way for the person looking at it
 * to know why. `isLikelyOAuthPopup()` is what tells those two cases apart.
 */
export function AppleSignInButton({ onCredential, onError }: AppleSignInButtonProps) {
  const [loading, setLoading] = useState(false);
  const [popupLoadFailed, setPopupLoadFailed] = useState(false);

  useEffect(() => {
    if (!isAppleLoginConfigured()) return;
    loadAppleIdServices()
      .then(initAppleAuth)
      .catch(() => {
        if (isLikelyOAuthPopup()) setPopupLoadFailed(true);
      });
  }, []);

  if (!isAppleLoginConfigured()) return null;

  if (popupLoadFailed) {
    return (
      <div className="w-full rounded-lg border border-border bg-warning-bg p-3 text-center text-sm text-ink">
        Sign in with Apple couldn't load. Close this window and try again.
        <button
          type="button"
          onClick={() => window.close()}
          className="mt-2 block w-full rounded-lg border border-gray-800 bg-black py-2 text-sm font-medium text-white"
        >
          Close this window
        </button>
      </div>
    );
  }

  async function handleClick() {
    setLoading(true);
    try {
      const auth = await loadAppleIdServices();
      initAppleAuth(auth);
      const response = await auth.signIn();
      const idToken = response.authorization?.id_token;
      if (!idToken) {
        onError('Sign in with Apple did not return a credential. Please try again.');
        return;
      }
      const name = response.user?.name;
      const fullName = name ? [name.firstName, name.lastName].filter(Boolean).join(' ') || null : null;
      await onCredential(idToken, fullName);
    } catch (err: any) {
      // The user closing the popup is not an error state -- nothing to report.
      if (err?.error === 'popup_closed_by_user') return;
      onError('Sign in with Apple is unavailable right now. Please try again later.');
    } finally {
      setLoading(false);
    }
  }

  return (
    // py-1.5, not the original py-2.5 (measured 42px vs GoogleSignInButton.tsx's 'large' at
    // 40px) -- auth redesign follow-up: reduced and matched against Google's button, which moved
    // to 'medium' (32px) in the same change. py-1.5 measures 34px, the closest standard Tailwind
    // spacing step to an exact match -- see GoogleSignInButton.tsx's own comment for how both
    // numbers were actually measured, not assumed from either SDK's docs.
    <button
      type="button"
      onClick={() => void handleClick()}
      disabled={loading}
      className="w-full flex items-center justify-center gap-2 rounded-lg border border-gray-800 bg-black text-white py-1.5 text-sm font-medium disabled:opacity-50"
    >
      <svg width="15" height="15" viewBox="0 0 170 170" fill="currentColor" aria-hidden="true">
        <path d="M150.37 130.25c-2.45 5.66-5.35 10.87-8.71 15.66-4.58 6.53-8.33 11.05-11.22 13.56-4.48 4.12-9.28 6.23-14.42 6.35-3.69 0-8.14-1.05-13.32-3.18-5.197-2.12-9.973-3.17-14.34-3.17-4.58 0-9.492 1.05-14.746 3.17-5.262 2.13-9.501 3.24-12.742 3.35-4.929.21-9.842-1.96-14.746-6.52-3.13-2.73-7.045-7.41-11.735-14.04-5.032-7.08-9.17-15.29-12.41-24.65-3.471-10.11-5.211-19.9-5.211-29.378 0-10.857 2.346-20.221 7.045-28.068 3.693-6.303 8.606-11.275 14.755-14.925 6.149-3.65 12.792-5.51 19.936-5.629 3.915 0 9.049 1.211 15.429 3.591 6.362 2.388 10.447 3.599 12.238 3.599 1.339 0 5.877-1.416 13.57-4.239 7.275-2.618 13.42-3.702 18.445-3.275 13.63 1.1 23.87 6.473 30.68 16.153-12.19 7.386-18.22 17.731-18.1 31.002.11 10.337 3.86 18.939 11.23 25.769 3.34 3.17 7.07 5.62 11.22 7.36-.9 2.61-1.85 5.11-2.86 7.51zM119.11 7.24c0 8.102-2.96 15.667-8.86 22.669-7.12 8.324-15.732 13.134-25.071 12.375a25.222 25.222 0 0 1-.188-3.07c0-7.778 3.386-16.102 9.399-22.908 3.002-3.446 6.822-6.311 11.45-8.597 4.62-2.25 8.99-3.5 13.1-3.71.12 1.083.17 2.166.17 3.24z"/>
      </svg>
      {loading ? 'Signing in…' : 'Sign in with Apple'}
    </button>
  );
}
