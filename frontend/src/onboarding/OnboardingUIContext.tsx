import { createContext, useContext, useState, type ReactNode } from 'react';

export type OnboardingStep = 'welcome' | 'focus' | 'tourIntro' | 'tour' | 'success';

interface OnboardingUIState {
  step: OnboardingStep;
  setStep: (step: OnboardingStep) => void;
}

const OnboardingUIContext = createContext<OnboardingUIState | null>(null);

export function OnboardingUIProvider({ children }: { children: ReactNode }) {
  const [step, setStep] = useState<OnboardingStep>('welcome');
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
