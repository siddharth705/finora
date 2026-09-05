import { useEffect, useRef, useState } from 'react';
import { isGoogleLoginConfigured, loadGoogleIdentityServices, type GoogleCredentialResponse } from '../lib/googleIdentity';

interface GoogleSignInButtonProps {
  // Google's own button copy differs by context ("Sign up with Google" vs "Sign in with
  // Google") -- see https://developers.google.com/identity/gsi/web/reference/js-reference#text.
  text: 'signup_with' | 'signin_with';
  // Receives the raw Google ID token credential. Left to the caller (Register.tsx/Login.tsx)
  // rather than handled here, so this component doesn't need to know about AuthContext,
  // navigation, or which of the two flows it's embedded in -- it only renders Google's button
  // and hands back what Google gave it.
  onCredential: (idToken: string) => void | Promise<void>;
  onError: (message: string) => void;
}

// D-23: renders Google's own Identity Services button. Deliberately NOT rendered at all when
// VITE_GOOGLE_LOGIN_CLIENT_ID is unset (isGoogleLoginConfigured()) -- same "unconfigured is a
// supported state, degrade silently" posture as BankLogo/MerchantLogo's Logo.dev fallback and
// Firebase's lazy init elsewhere in this codebase, rather than shipping a button that can't work.
export function GoogleSignInButton({ text, onCredential, onError }: GoogleSignInButtonProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [ready, setReady] = useState(false);
  // Held in a ref, not a dependency of the initialize() effect below, so a parent re-render
  // (e.g. the page's own `loading` state flipping while a credential is being processed) doesn't
  // re-run initialize()/renderButton() and flicker Google's own button.
  const onCredentialRef = useRef(onCredential);
  onCredentialRef.current = onCredential;
  const onErrorRef = useRef(onError);
  onErrorRef.current = onError;

  useEffect(() => {
    if (!isGoogleLoginConfigured() || !containerRef.current) return;
    let cancelled = false;
    let resizeObserver: ResizeObserver | null = null;

    loadGoogleIdentityServices()
      .then((accountsId) => {
        if (cancelled || !containerRef.current) return;
        accountsId.initialize({
          client_id: import.meta.env.VITE_GOOGLE_LOGIN_CLIENT_ID!,
          callback: (response: GoogleCredentialResponse) => {
            void onCredentialRef.current(response.credential);
          },
        });

        // GIS requires a pixel value here, not a percentage -- '100%' produced a silent
        // "[GSI_LOGGER]: Provided button width is invalid" console warning in production and fell
        // back to some GIS-internal default. A ONE-TIME measurement right when this promise
        // resolves is a race: production showed a button locked at 107px next to a full-width
        // Apple button, because the container's layout (grid columns, web fonts) hadn't finished
        // settling yet at that instant. A ResizeObserver re-renders whenever the container's real,
        // settled width changes, instead of trusting whatever width happened to be laid out first.
        let lastWidth = 0;
        const render = (entries: ResizeObserverEntry[]) => {
          if (!containerRef.current) return;
          const contentWidth = entries[0]?.contentRect.width ?? 0;
          // Capped at Google's documented max: https://developers.google.com/identity/gsi/web/reference/js-reference#width
          const measuredWidth = Math.min(Math.round(contentWidth), 400);
          if (measuredWidth === 0 || measuredWidth === lastWidth) return;
          lastWidth = measuredWidth;
          containerRef.current.replaceChildren();
          accountsId.renderButton(containerRef.current, {
            theme: 'outline',
            // 'medium', not the default 'large' -- auth redesign follow-up: matched against
            // AppleSignInButton.tsx's own height instead of trusting either default.
            size: 'medium',
            width: String(measuredWidth),
            text,
            // Google defaults to a left-pinned logo with the text centered in the remaining
            // space; AppleSignInButton centers its icon+text as one unit. 'center' is the closest
            // GIS gets to matching that without abandoning Google's own rendered button.
            logo_alignment: 'center',
          });
          setReady(true);
        };

        resizeObserver = new ResizeObserver(render);
        resizeObserver.observe(containerRef.current);
      })
      .catch(() => {
        if (!cancelled) onErrorRef.current('Sign in with Google is unavailable right now. Please try again later.');
      });

    return () => {
      cancelled = true;
      resizeObserver?.disconnect();
    };
    // text intentionally omitted: Register.tsx and Login.tsx each mount their own instance with a
    // fixed text prop that never changes across that instance's lifetime.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (!isGoogleLoginConfigured()) return null;

  return (
    <div>
      {/* Google measures and draws its own button into this div once renderButton() runs --
          fixed height reserves the space up front so the rest of the form doesn't jump once it
          appears. */}
      <div ref={containerRef} className="w-full min-h-[40px]" aria-busy={!ready} />
    </div>
  );
}
