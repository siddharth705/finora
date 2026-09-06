import { createContext, useContext, useState, type ReactNode } from 'react';

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
