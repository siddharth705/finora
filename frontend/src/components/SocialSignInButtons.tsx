import { useState } from 'react';
import { GoogleSignInButton } from './GoogleSignInButton';
import { AppleSignInButton } from './AppleSignInButton';

interface SocialSignInButtonsProps {
  googleText: 'signup_with' | 'signin_with';
  onGoogleCredential: (idToken: string) => void | Promise<void>;
  onAppleCredential: (idToken: string, fullName: string | null) => void | Promise<void>;
  onError: (message: string) => void;
}

// The only parent IdentifyStep/RegisterStep/PasswordStep had in common for these two buttons used
// to be duplicated JSX with no relationship between them, which is how a real width mismatch
// (see GoogleSignInButton.tsx's onRenderedWidth comment) went unnoticed: Google's button is
// capped at ~400-420px by Google itself, Apple's plain-CSS button has no such cap and stretches
// to the full card width, and nothing tied the two together. This component is that missing
// shared parent -- it tracks Google's real rendered width and applies that same number to
// Apple's, so a future Google rendering change can't silently reintroduce the gap.
export function SocialSignInButtons({ googleText, onGoogleCredential, onAppleCredential, onError }: SocialSignInButtonsProps) {
  const [googleWidth, setGoogleWidth] = useState<number | null>(null);

  return (
    <>
      <GoogleSignInButton text={googleText} onCredential={onGoogleCredential} onError={onError} onRenderedWidth={setGoogleWidth} />
      {/* Full width (matching the form) until Google's real width is known, same "reserve space,
          then settle" pattern GoogleSignInButton already uses for its own placeholder. */}
      <div className="mt-3" style={googleWidth ? { width: googleWidth, marginInline: 'auto' } : undefined}>
        <AppleSignInButton onCredential={onAppleCredential} onError={onError} />
      </div>
    </>
  );
}
