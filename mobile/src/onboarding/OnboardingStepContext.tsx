import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { useAuth } from '../context/AuthContext';

// Mobile counterpart of frontend/src/onboarding/OnboardingUIContext.tsx -- same reason it exists:
// RootNavigator needs to know which onboarding step is active (specifically, whether it's 'tour')
// so it can render the REAL AppTabs underneath the spotlight instead of a copy of it, the same
// design correction made on web (see that file's own comment, and the design spec's §7 addendum
// on mobile's tab-bar-navigation-aware tour). OnboardingNavigator drives this state; RootNavigator
// only reads it.
export type OnboardingStep = 'welcome' | 'focus' | 'tourIntro' | 'tour' | 'success';

interface OnboardingStepState {
  step: OnboardingStep;
  setStep: (step: OnboardingStep) => void;
}

const OnboardingStepContext = createContext<OnboardingStepState | null>(null);

export function OnboardingStepProvider({ children }: { children: ReactNode }) {
  const [step, setStep] = useState<OnboardingStep>('welcome');
  const { token, onboardingCompleted } = useAuth();
  // Bug fix: same as frontend/src/onboarding/OnboardingUIContext.tsx's own comment -- `step`
  // otherwise survives past the session that set it (a logout followed by a different user
  // logging in, or Settings' "Retake Product Tour" flipping onboardingCompleted back to false),
  // and RootNavigator reads `step` but never resets it. Without this, the next thing to render
  // resumes at whatever step the PREVIOUS session last reached (typically 'success'), skipping
  // Welcome/Focus/Tour entirely.
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
    <OnboardingStepContext.Provider value={{ step, setStep }}>
      {children}
    </OnboardingStepContext.Provider>
  );
}

export function useOnboardingStep() {
  const ctx = useContext(OnboardingStepContext);
  if (!ctx) throw new Error('useOnboardingStep must be used within OnboardingStepProvider');
  return ctx;
}
