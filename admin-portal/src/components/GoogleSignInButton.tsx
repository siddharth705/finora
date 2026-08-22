import { useEffect, useRef, useState } from 'react';
import { isGoogleLoginConfigured, loadGoogleIdentityServices, type GoogleCredentialResponse } from '../lib/googleIdentity';

interface GoogleSignInButtonProps {
  text: 'signup_with' | 'signin_with';
  // Receives the raw Google ID token credential. Left to the caller rather than handled here, so
  // this component doesn't need to know about AdminAuthContext or which flow it's embedded in --
  // it only renders Google's button and hands back what Google gave it.
  onCredential: (idToken: string) => void | Promise<void>;
  onError: (message: string) => void;
}

// Ported verbatim from frontend/src/components/GoogleSignInButton.tsx (Admin MFA UI, SEC-03).
// Deliberately NOT rendered at all when VITE_GOOGLE_LOGIN_CLIENT_ID is unset
// (isGoogleLoginConfigured()) -- same "unconfigured is a supported state, degrade silently"
// posture as this app's other optional integrations, rather than shipping a button that can't work.
export function GoogleSignInButton({ text, onCredential, onError }: GoogleSignInButtonProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [ready, setReady] = useState(false);
  // Held in a ref, not a dependency of the initialize() effect below, so a parent re-render
  // doesn't re-run initialize()/renderButton() and flicker Google's own button.
  const onCredentialRef = useRef(onCredential);
  onCredentialRef.current = onCredential;
  const onErrorRef = useRef(onError);
  onErrorRef.current = onError;

  useEffect(() => {
    if (!isGoogleLoginConfigured() || !containerRef.current) return;
    let cancelled = false;

    loadGoogleIdentityServices()
      .then((accountsId) => {
        if (cancelled || !containerRef.current) return;
        accountsId.initialize({
          client_id: import.meta.env.VITE_GOOGLE_LOGIN_CLIENT_ID!,
          callback: (response: GoogleCredentialResponse) => {
            void onCredentialRef.current(response.credential);
          },
        });
        // GIS requires a pixel value here, not a percentage -- '100%' produces a silent
        // "[GSI_LOGGER]: Provided button width is invalid" console warning and falls back to some
        // GIS-internal default instead of actually filling the container. Measure the real
        // rendered width and cap at Google's documented max of 400px:
        // https://developers.google.com/identity/gsi/web/reference/js-reference#width
        const measuredWidth = Math.min(Math.round(containerRef.current.getBoundingClientRect().width), 400);
        accountsId.renderButton(containerRef.current, {
          theme: 'outline',
          size: 'large',
          width: String(measuredWidth),
          text,
        });
        setReady(true);
      })
      .catch(() => {
        if (!cancelled) onErrorRef.current('Sign in with Google is unavailable right now. Please try again later.');
      });

    return () => {
      cancelled = true;
    };
    // text intentionally omitted: each mount uses a fixed text prop that never changes across
    // that instance's lifetime.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (!isGoogleLoginConfigured()) return null;

  return (
    <div>
      {/* Google measures and draws its own button into this div once renderButton() runs --
          fixed min-height reserves the space up front so the rest of the form doesn't jump once
          it appears. */}
      <div ref={containerRef} className="w-full min-h-[40px]" aria-busy={!ready} />
    </div>
  );
}
