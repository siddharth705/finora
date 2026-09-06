import { render, screen, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { useAuth } from '../context/AuthContext';
import { OnboardingUIProvider, useOnboardingUI } from './OnboardingUIContext';

// Same reasoning as ProtectedRoute.test.tsx's own mock of this hook: OnboardingUIProvider now
// reads token/onboardingCompleted from it (to reset `step` on logout/retake -- see the provider's
// own comment), and mocking it directly keeps this test focused on that reset logic instead of
// AuthProvider's own bootstrap/localStorage machinery.
vi.mock('../context/AuthContext', () => ({
  useAuth: vi.fn(),
}));

function Probe() {
  const { step, setStep } = useOnboardingUI();
  return <button onClick={() => setStep('tour')}>{step}</button>;
}

function renderProbe() {
  return render(<OnboardingUIProvider><Probe /></OnboardingUIProvider>);
}

describe('OnboardingUIContext', () => {
  beforeEach(() => {
    vi.mocked(useAuth).mockReset().mockReturnValue({
      token: 'tok', onboardingCompleted: false,
    } as ReturnType<typeof useAuth>);
  });

  it('starts at the welcome step and updates on setStep', () => {
    renderProbe();
    expect(screen.getByText('welcome')).toBeInTheDocument();
    act(() => screen.getByRole('button').click());
    expect(screen.getByText('tour')).toBeInTheDocument();
  });

  // Bug fix regression test: `step` used to survive past the session that set it. Retaking the
  // tour (Settings) flips onboardingCompleted true -> false without unmounting this provider --
  // without the reset, a step left at 'tour' would render OnboardingFlow at the wrong step instead
  // of restarting at Welcome.
  it('resets to welcome when onboardingCompleted flips from true to false (retake)', () => {
    vi.mocked(useAuth).mockReturnValue({ token: 'tok', onboardingCompleted: true } as ReturnType<typeof useAuth>);
    const { rerender } = renderProbe();
    act(() => screen.getByRole('button').click());
    expect(screen.getByText('tour')).toBeInTheDocument();

    vi.mocked(useAuth).mockReturnValue({ token: 'tok', onboardingCompleted: false } as ReturnType<typeof useAuth>);
    rerender(<OnboardingUIProvider><Probe /></OnboardingUIProvider>);

    expect(screen.getByText('welcome')).toBeInTheDocument();
  });

  // Bug fix regression test: a logout followed by a different user logging in (no page reload,
  // same SPA session) left `step` at whatever the FIRST user's session last reached -- the second
  // user's onboarding could open mid-flow, or skip straight to Success.
  it('resets to welcome when the token goes from present to null (logout)', () => {
    vi.mocked(useAuth).mockReturnValue({ token: 'tok', onboardingCompleted: false } as ReturnType<typeof useAuth>);
    const { rerender } = renderProbe();
    act(() => screen.getByRole('button').click());
    expect(screen.getByText('tour')).toBeInTheDocument();

    vi.mocked(useAuth).mockReturnValue({ token: null, onboardingCompleted: false } as ReturnType<typeof useAuth>);
    rerender(<OnboardingUIProvider><Probe /></OnboardingUIProvider>);

    expect(screen.getByText('welcome')).toBeInTheDocument();
  });

  it('does not reset step on the initial mount of an incomplete, already-authenticated session', () => {
    vi.mocked(useAuth).mockReturnValue({ token: 'tok', onboardingCompleted: false } as ReturnType<typeof useAuth>);
    renderProbe();
    act(() => screen.getByRole('button').click());
    expect(screen.getByText('tour')).toBeInTheDocument();
  });
});
