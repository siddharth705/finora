import { useEffect, useState } from 'react';
import { Link, useLocation, useSearchParams, useNavigate } from 'react-router-dom';
import { ShieldCheck } from 'lucide-react';
import { BrandMark } from '../components/BrandMark';
import { MarketingPanel } from './auth-entry/MarketingPanel';
import { IdentifyStep } from './auth-entry/IdentifyStep';
import { PasswordStep } from './auth-entry/PasswordStep';
import { RegisterStep } from './auth-entry/RegisterStep';

type Step = 'identify' | 'password' | 'register';

interface DeepLinkState {
  identifier?: string;
  banner?: string;
  skipToPassword?: boolean;
}

/**
 * Unified authentication entry page (auth/security review §2.2 / Phase 3, collapsed into a
 * single screen 2026-08-24 -- see docs/superpowers/specs/2026-08-24-unified-auth-entry-design.md).
 * /auth is the only route a user navigates to; /login and /register are redirects (App.tsx) kept
 * only for old bookmarks/links. Every transition below is UI-only -- see the spec's Security
 * boundaries section: `step` and `identifier` never substitute for a real backend call. Each step
 * component still posts to the real endpoint it always did (/auth/identify, /auth/login,
 * /auth/register, /auth/google, /auth/apple) and only advances on that call's own success.
 */
export default function AuthEntry() {
  const location = useLocation();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  // D-28 PR4-C, unchanged from Register.tsx: a referral link's `?ref=` is only meaningful for the
  // signup this page load represents.
  const referralCode = searchParams.get('ref') ?? undefined;

  const deepLink = location.state as DeepLinkState | null;
  const [step, setStep] = useState<Step>(deepLink?.skipToPassword ? 'password' : 'identify');
  const [identifier, setIdentifier] = useState(deepLink?.identifier ?? '');
  const [banner, setBanner] = useState<string | null>(deepLink?.banner ?? null);
  const [registerPrefill, setRegisterPrefill] = useState<{ email?: string; phoneNumber?: string }>({});

  // Reset to a safe default on a back/forward-cache restore -- some browsers restore this exact
  // live component instance (not a fresh mount) when the user hits Forward after Back, which would
  // otherwise silently show whatever step/password/reactivation state was on screen when they left.
  // A genuine reload is unaffected: it always remounts from scratch, so `useState`'s initial values
  // (including the deep-link case above, which browsers persist across a reload of that same
  // history entry) already do the right thing without this handler.
  useEffect(() => {
    function handlePageShow(event: Event) {
      if ((event as PageTransitionEvent).persisted) {
        setStep('identify');
        setIdentifier('');
        setBanner(null);
        setRegisterPrefill({});
      }
    }
    window.addEventListener('pageshow', handlePageShow);
    return () => window.removeEventListener('pageshow', handlePageShow);
  }, []);

  function afterAuthSuccess(phoneVerified: boolean) {
    void navigate(phoneVerified ? '/app' : '/verify-phone', { state: phoneVerified ? undefined : { fromLogin: true } });
  }

  function handleExists(existingIdentifier: string) {
    setIdentifier(existingIdentifier);
    setBanner(null);
    setStep('password');
  }

  function handleContinue(newIdentifier: string, prefill: { email?: string; phoneNumber?: string }) {
    setIdentifier(newIdentifier);
    setRegisterPrefill(prefill);
    setStep('register');
  }

  // 409 from RegisterStep -- the DB unique constraint is the real authority here, not the earlier
  // /auth/identify CONTINUE (see the spec's "registration race" note). Replaces today's Register.tsx
  // "Continue to login" link with an in-place step switch.
  function handleAccountExists(existingIdentifier: string) {
    setIdentifier(existingIdentifier);
    setBanner('This email or mobile number already has an account — sign in below.');
    setStep('password');
  }

  // Clears everything transient, not just the step -- identifier, and (by unmounting PasswordStep/
  // RegisterStep) their own local password/confirmPassword/error/reactivation-token/OAuth-in-flight
  // state along with them. Matters on shared computers.
  function handleNotYou() {
    setStep('identify');
    setIdentifier('');
    setBanner(null);
    setRegisterPrefill({});
  }

  const marketingCopy = step === 'password'
    ? { badge: 'Welcome back', headline: <>Pick up right where you <span className="text-primary">left off</span></>, description: 'Sign in to see your latest transactions, budgets, goals and AI-powered insights — all in one place.' }
    : { badge: 'Your finances, finally in one place', headline: <>Take control of your money with <span className="text-primary">Fynora</span></>, description: 'Import statements, track spending, set budgets and get AI-powered insights to build a better financial future.' };

  return (
    <div className="relative overflow-hidden min-h-screen bg-bg flex flex-col items-center justify-center p-4 lg:p-8 gap-6">
      {/* Auth redesign Phase 3: two soft, slow-drifting brand-toned circles behind the content --
          purely decorative (aria-hidden, no pointer events), reusing the .float-slow/.float-slower
          keyframes already defined for the marketing page rather than inventing new ones. Sits at
          the page level (not inside the card) so it drifts behind both the marketing panel and the
          form, same as the mobile equivalent (AuthAmbientBackground). */}
      <div className="absolute inset-0 pointer-events-none" aria-hidden="true">
        <div className="absolute -top-24 -left-24 w-96 h-96 rounded-full bg-primary-light blur-3xl opacity-40 float-slow" />
        <div className="absolute -bottom-24 -right-24 w-96 h-96 rounded-full bg-primary-light blur-3xl opacity-30 float-slower" />
      </div>

      <div className="relative w-full max-w-6xl grid lg:grid-cols-2 gap-10 lg:gap-16 items-center">
        <MarketingPanel {...marketingCopy} />

        <div
          className="auth-reveal bg-card rounded-xl2 p-8 w-full shadow-soft border border-border"
          style={{ animationDelay: '80ms' }}
        >
          <div className="flex items-center gap-2 mb-6 lg:hidden">
            <Link to="/" className="flex items-center gap-2 w-fit">
              <BrandMark size={28} variant="auto" className="rounded-lg" />
              <span className="font-display font-extrabold tracking-wide text-ink">FYNORA</span>
            </Link>
          </div>

          {step === 'identify' && (
            <IdentifyStep onExists={handleExists} onContinue={handleContinue} onSuccess={afterAuthSuccess} />
          )}
          {step === 'password' && (
            <PasswordStep identifier={identifier} banner={banner} onSuccess={afterAuthSuccess} onNotYou={handleNotYou} />
          )}
          {step === 'register' && (
            <RegisterStep prefill={registerPrefill} referralCode={referralCode} onSuccess={afterAuthSuccess} onAccountExists={handleAccountExists} />
          )}
        </div>
      </div>

      <p className="text-xs text-muted flex items-center gap-2">
        <ShieldCheck size={13} /> Bank-grade encryption. Your data is never sold.
      </p>
    </div>
  );
}
