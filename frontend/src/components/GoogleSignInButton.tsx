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
  // Called with Google's own rendered button width once it's known, and again whenever it
  // changes -- see the comment above the iframe ResizeObserver below for why a caller needs this
  // at all instead of just reading the (documented, capped-at-400) `width` param back.
  onRenderedWidth?: (px: number) => void;
}

// D-23: renders Google's own Identity Services button. Deliberately NOT rendered at all when
// VITE_GOOGLE_LOGIN_CLIENT_ID is unset (isGoogleLoginConfigured()) -- same "unconfigured is a
// supported state, degrade silently" posture as BankLogo/MerchantLogo's Logo.dev fallback and
// Firebase's lazy init elsewhere in this codebase, rather than shipping a button that can't work.
export function GoogleSignInButton({ text, onCredential, onError, onRenderedWidth }: GoogleSignInButtonProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [ready, setReady] = useState(false);
  // Held in a ref, not a dependency of the initialize() effect below, so a parent re-render
  // (e.g. the page's own `loading` state flipping while a credential is being processed) doesn't
  // re-run initialize()/renderButton() and flicker Google's own button.
  const onCredentialRef = useRef(onCredential);
  onCredentialRef.current = onCredential;
  const onErrorRef = useRef(onError);
  onErrorRef.current = onError;
  const onRenderedWidthRef = useRef(onRenderedWidth);
  onRenderedWidthRef.current = onRenderedWidth;

  useEffect(() => {
    if (!isGoogleLoginConfigured() || !containerRef.current) return;
    let cancelled = false;
    let resizeObserver: ResizeObserver | null = null;
    let iframeResizeObserver: ResizeObserver | null = null;

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
            // 'medium', not the default 'large' -- Google's own tier ordering (small < medium <
            // large, per https://developers.google.com/identity/gsi/web/reference/js-reference#size)
            // makes this a real reduction regardless of exact pixel values, which Google doesn't
            // publish and which this component can't observe ahead of render (GIS never reports
            // its own rendered height back to the caller). The 44px this actually renders at in
            // production (measured live on app.fynora.net with the real client_id/origin -- a
            // synthetic test page with a placeholder client_id skips Google's real sizing path
            // and is not representative) is what min-h-[44px] below and
            // AppleSignInButton.tsx's py-2.5 are matched against.
            size: 'medium',
            width: String(measuredWidth),
            text,
            // Google defaults to a left-pinned logo with the text centered in the remaining
            // space; AppleSignInButton centers its icon+text as one unit. 'center' is the closest
            // GIS gets to matching that without abandoning Google's own rendered button.
            logo_alignment: 'center',
          });
          setReady(true);

          // Google's `width` param above is capped at 400 (its own documented max), but the
          // iframe it actually draws doesn't come back at exactly that number -- measured live on
          // app.fynora.net, requesting 400 rendered a 420px-wide iframe. That gap is why a
          // full-width Apple button (no such cap) used to look visibly longer than Google's next
          // to it: matching Apple to the *requested* 400 would still leave a real, visible ~20px
          // difference. Reporting the iframe's own real rendered width, not the number we asked
          // Google for, is what lets a parent (SocialSignInButtons) size Apple to match reality
          // instead of a number Google doesn't actually honor.
          iframeResizeObserver?.disconnect();
          const iframe = containerRef.current.querySelector('iframe');
          if (iframe) {
            iframeResizeObserver = new ResizeObserver((iframeEntries) => {
              const width = iframeEntries[0]?.contentRect.width;
              if (width) onRenderedWidthRef.current?.(width);
            });
            iframeResizeObserver.observe(iframe);
          }
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
      iframeResizeObserver?.disconnect();
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
      <div ref={containerRef} className="w-full min-h-[44px]" aria-busy={!ready} />
    </div>
  );
}
