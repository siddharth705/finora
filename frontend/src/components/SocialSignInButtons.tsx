import { GoogleSignInButton } from './GoogleSignInButton';
import { AppleSignInButton } from './AppleSignInButton';

interface SocialSignInButtonsProps {
  googleText: 'signup_with' | 'signin_with';
  onGoogleCredential: (idToken: string) => void | Promise<void>;
  onAppleCredential: (idToken: string, fullName: string | null) => void | Promise<void>;
  onError: (message: string) => void;
  // Google's own real rendered width, once known -- see GoogleSignInButton.tsx's onRenderedWidth
  // comment for why this can't just be the (documented, capped-at-400) `width` param instead.
  // A first version of this component applied that width to Apple's button locally (centering it
  // with its own margin-auto), which fixed the size mismatch but introduced a new one: Google's
  // button is naturally left-aligned within its container, so a separately-centered Apple button
  // no longer shared its left edge. Forwarding the width up instead lets the parent form narrow
  // *itself* to match -- Apple's existing `w-full` class then sizes it correctly with no styling
  // of its own, sharing the same left edge as everything else in that narrower form by
  // construction rather than by a second coordinate this component would have to get right too.
  onWidthKnown: (px: number) => void;
}

// The only parent IdentifyStep/RegisterStep/PasswordStep had in common for these two buttons used
// to be duplicated JSX with no relationship between them, which is how a real width mismatch
// (see GoogleSignInButton.tsx's onRenderedWidth comment) went unnoticed: Google's button is
// capped at ~400-420px by Google itself, Apple's plain-CSS button has no such cap and stretches
// to the full card width, and nothing tied the two together. This component is that missing
// shared parent -- it forwards Google's real rendered width to the caller via onWidthKnown so the
// whole form can narrow to match, rather than trying to fix up just these two buttons in
// isolation (see onWidthKnown's own comment for why that narrower fix didn't hold up).
export function SocialSignInButtons({ googleText, onGoogleCredential, onAppleCredential, onError, onWidthKnown }: SocialSignInButtonsProps) {
  return (
    <>
      <GoogleSignInButton text={googleText} onCredential={onGoogleCredential} onError={onError} onRenderedWidth={onWidthKnown} />
      <div className="mt-3">
        <AppleSignInButton onCredential={onAppleCredential} onError={onError} />
      </div>
    </>
  );
}
