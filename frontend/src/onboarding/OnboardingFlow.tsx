import { onboardingApi } from '../api/endpoints';
import { useAuth } from '../context/AuthContext';
import { useOnboardingUI } from './OnboardingUIContext';
import { WelcomeScreen } from './WelcomeScreen';
import { FinancialFocusScreen } from './FinancialFocusScreen';

export function OnboardingFlow() {
  const { step, setStep } = useOnboardingUI();
  const { setOnboardingCompleted } = useAuth();

  async function finishOnboarding() {
    await onboardingApi.complete();
    setOnboardingCompleted(true);
  }

  async function skipEverything() {
    await finishOnboarding();
  }

  async function submitFocusAndContinue(selected: string[]) {
    await onboardingApi.setFinancialFocus(selected);
    setStep('tourIntro');
  }

  if (step === 'welcome') {
    return <WelcomeScreen onStart={() => setStep('focus')} onSkip={skipEverything} />;
  }
  if (step === 'focus') {
    return <FinancialFocusScreen onContinue={submitFocusAndContinue} />;
  }
  // 'tourIntro' lands in Task 10; 'tour' is rendered by ProtectedRoute directly, never by
  // OnboardingFlow; 'success' lands in Task 11.
  return <div data-testid="onboarding-flow">Onboarding flow placeholder</div>;
}
