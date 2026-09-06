import { Text, View, StyleSheet } from 'react-native';
import { onboardingApi } from '../api/endpoints';
import { useAuth } from '../context/AuthContext';
import { Button } from '../components/Button';
import { useTheme } from '../theme';
import { useOnboardingStep } from './OnboardingStepContext';
import { WelcomeScreen } from './WelcomeScreen';
import { FinancialFocusScreen } from './FinancialFocusScreen';
import { SuccessScreen } from './SuccessScreen';

// Renders every onboarding step EXCEPT 'tour' -- RootNavigator intercepts that one directly and
// renders the real AppTabs plus TourOverlay on top instead, so the tour spotlights the live app
// rather than a copy of it (same design correction as frontend/src/components/ProtectedRoute.tsx;
// see that file's own comment). This component only ever sees 'welcome' | 'focus' | 'tourIntro' |
// 'success' in practice.
export function OnboardingNavigator() {
  const { step, setStep } = useOnboardingStep();
  const { setOnboardingCompleted } = useAuth();
  const c = useTheme();

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
      <View style={[styles.container, { backgroundColor: c.bg }]}>
        <Text style={[styles.title, { color: c.ink }]}>Let's take a quick tour</Text>
        <Text style={[styles.subtitle, { color: c.muted }]}>
          This will only take about 30 seconds and will help you get the most out of Fynora.
        </Text>
        <Button label="Start Tour" onPress={() => setStep('tour')} />
        <View style={{ height: 8 }} />
        <Button label="Skip" onPress={() => setStep('success')} variant="link" />
      </View>
    );
  }
  if (step === 'success') {
    return <SuccessScreen onDone={finishOnboarding} />;
  }
  // 'tour': never actually rendered by this component (RootNavigator intercepts it), but a
  // non-null fallback here is cheap insurance against a render racing a step change.
  return null;
}

const styles = StyleSheet.create({
  container: { flex: 1, justifyContent: 'center', alignItems: 'center', padding: 24 },
  title: { fontSize: 22, fontWeight: '700', marginBottom: 8, textAlign: 'center' },
  subtitle: { fontSize: 14, textAlign: 'center', marginBottom: 24 },
});
