import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { useAuth } from '../context/AuthContext';

export type OnboardingStep = 'welcome' | 'focus' | 'tourIntro' | 'tour' | 'success';

interface OnboardingUIState {
  step: OnboardingStep;
  setStep: (step: OnboardingStep) => void;
}

const OnboardingUIContext = createContext<OnboardingUIState | null>(null);

export function OnboardingUIProvider({ children }: { children: ReactNode }) {
  const [step, setStep] = useState<OnboardingStep>('welcome');
  const { token, onboardingCompleted } = useAuth();
  // Bug fix: `step` used to persist for the lifetime of the tab, past whatever session set it.
  // Logging out and a different user logging back in (or Settings' "Retake Product Tour", which
  // flips onboardingCompleted back to false without a page reload) both left `step` wherever the
  // PREVIOUS run last set it -- ProtectedRoute reads `step` but never resets it, so a session that
  // reaches this provider with step still at 'success' renders the Success screen immediately,
  // skipping Welcome/Focus/Tour entirely. Watching both transitions here, once, is the fix: a
  // logout (token non-null -> null) or a retake (onboardingCompleted true -> false) both mean
  // whatever's rendered next should start the flow over, not resume someone else's place in it.
  const prevRef = useRef({ token, onboardingCompleted });
  useEffect(() => {
    const prev = prevRef.current;
    const loggedOut = prev.token !== null && token === null;
    const retookOnboarding = prev.onboardingCompleted && !onboardingCompleted;
    if (loggedOut || retookOnboarding) {
      setStep('welcome');
    }
    prevRef.current = { token, onboardingCompleted };
  }, [token, onboardingCompleted]);

  return (
    <OnboardingUIContext.Provider value={{ step, setStep }}>
      {children}
    </OnboardingUIContext.Provider>
  );
}

export function useOnboardingUI() {
  const ctx = useContext(OnboardingUIContext);
  if (!ctx) throw new Error('useOnboardingUI must be used within OnboardingUIProvider');
  return ctx;
}
