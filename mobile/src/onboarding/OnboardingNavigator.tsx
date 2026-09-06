import { useState } from 'react';
import { View, Text } from 'react-native';
import { onboardingApi } from '../api/endpoints';
import { useAuth } from '../context/AuthContext';
import { TourTargetProvider } from './TourTargetRegistry';
import { WelcomeScreen } from './WelcomeScreen';
import { FinancialFocusScreen } from './FinancialFocusScreen';
import { SuccessScreen } from './SuccessScreen';

type Step = 'welcome' | 'focus' | 'tourIntro' | 'tour' | 'success';

function OnboardingSteps() {
  const [step, setStep] = useState<Step>('welcome');
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
  if (step === 'success') {
    return <SuccessScreen onDone={finishOnboarding} />;
  }
  // 'tourIntro'/'tour' land in Task 16.
  return <View testID="onboarding-navigator"><Text>Onboarding placeholder</Text></View>;
}

export function OnboardingNavigator() {
  return (
    <TourTargetProvider>
      <OnboardingSteps />
    </TourTargetProvider>
  );
}
