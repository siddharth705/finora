import { render, screen, act } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { OnboardingUIProvider, useOnboardingUI } from './OnboardingUIContext';

function Probe() {
  const { step, setStep } = useOnboardingUI();
  return <button onClick={() => setStep('tour')}>{step}</button>;
}

describe('OnboardingUIContext', () => {
  it('starts at the welcome step and updates on setStep', () => {
    render(<OnboardingUIProvider><Probe /></OnboardingUIProvider>);
    expect(screen.getByText('welcome')).toBeInTheDocument();
    act(() => screen.getByRole('button').click());
    expect(screen.getByText('tour')).toBeInTheDocument();
  });
});
