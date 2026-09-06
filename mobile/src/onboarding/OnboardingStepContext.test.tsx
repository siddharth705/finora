import { render, screen, fireEvent } from '@testing-library/react-native';
import { Pressable, Text } from 'react-native';
import { useAuth } from '../context/AuthContext';
import { OnboardingStepProvider, useOnboardingStep } from './OnboardingStepContext';

// Same reasoning as SettingsScreen.test.tsx's own mock of this module: OnboardingStepProvider now
// reads token/onboardingCompleted from it (to reset `step` on logout/retake -- see the provider's
// own comment), and mocking it directly avoids the real AuthContext's transitive
// react-native-purchases import hitting the pre-existing unbuilt-ESM Jest gap RootNavigator's own
// native-stack mock comment documents.
jest.mock('../context/AuthContext', () => ({
  useAuth: jest.fn(),
}));

function Probe() {
  const { step, setStep } = useOnboardingStep();
  return <Pressable accessibilityRole="button" onPress={() => setStep('tour')}><Text>{step}</Text></Pressable>;
}

function renderProbe() {
  return render(<OnboardingStepProvider><Probe /></OnboardingStepProvider>);
}

describe('OnboardingStepContext', () => {
  beforeEach(() => {
    (useAuth as jest.Mock).mockReset().mockReturnValue({ token: 'tok', onboardingCompleted: false });
  });

  it('starts at the welcome step and updates on setStep', () => {
    renderProbe();
    expect(screen.getByText('welcome')).toBeTruthy();
    fireEvent.press(screen.getByText('welcome'));
    expect(screen.getByText('tour')).toBeTruthy();
  });

  // Bug fix regression test: `step` used to survive past the session that set it. Retaking the
  // tour (SettingsScreen) flips onboardingCompleted true -> false without unmounting this
  // provider -- without the reset, a step left at 'tour' would render OnboardingNavigator at the
  // wrong step instead of restarting at Welcome.
  it('resets to welcome when onboardingCompleted flips from true to false (retake)', () => {
    (useAuth as jest.Mock).mockReturnValue({ token: 'tok', onboardingCompleted: true });
    const { rerender } = renderProbe();
    fireEvent.press(screen.getByText('welcome'));
    expect(screen.getByText('tour')).toBeTruthy();

    (useAuth as jest.Mock).mockReturnValue({ token: 'tok', onboardingCompleted: false });
    rerender(<OnboardingStepProvider><Probe /></OnboardingStepProvider>);

    expect(screen.getByText('welcome')).toBeTruthy();
  });

  // Bug fix regression test: a logout followed by a different user logging in (no app restart,
  // same RootNavigator tree) left `step` at whatever the FIRST user's session last reached.
  it('resets to welcome when the token goes from present to null (logout)', () => {
    (useAuth as jest.Mock).mockReturnValue({ token: 'tok', onboardingCompleted: false });
    const { rerender } = renderProbe();
    fireEvent.press(screen.getByText('welcome'));
    expect(screen.getByText('tour')).toBeTruthy();

    (useAuth as jest.Mock).mockReturnValue({ token: null, onboardingCompleted: false });
    rerender(<OnboardingStepProvider><Probe /></OnboardingStepProvider>);

    expect(screen.getByText('welcome')).toBeTruthy();
  });
});
