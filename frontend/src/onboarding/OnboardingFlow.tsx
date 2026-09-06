import { onboardingApi } from '../api/endpoints';
import { useAuth } from '../context/AuthContext';
import { Button } from '../design-system';
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
  if (step === 'tourIntro') {
    return (
      <div className="flex flex-col items-center justify-center min-h-screen px-6 text-center">
        <h1 className="text-2xl font-bold text-ink mb-2">Let's take a quick tour</h1>
        <p className="text-muted mb-8">
          This will only take about 30 seconds and will help you get the most out of Fynora.
        </p>
        <div className="flex gap-3">
          <Button variant="primary" onClick={() => setStep('tour')}>Start Tour</Button>
          <Button variant="secondary" onClick={() => setStep('success')}>Skip</Button>
        </div>
      </div>
    );
  }
  // 'tour' is rendered by ProtectedRoute directly, never by OnboardingFlow -- see that
  // component's own comment. 'success' lands in Task 11.
  return <div data-testid="onboarding-flow">Onboarding flow placeholder</div>;
}
