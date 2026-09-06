import { render, screen, fireEvent, act } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { describe, it, expect, vi } from 'vitest';
import { SuccessScreen } from './SuccessScreen';

describe('SuccessScreen', () => {
  it('shows all 6 checklist items unchecked', () => {
    render(<MemoryRouter><SuccessScreen onDone={vi.fn()} /></MemoryRouter>);
    expect(screen.getByText(/Complete your profile/)).toBeInTheDocument();
    expect(screen.getByText(/Import first statement/)).toBeInTheDocument();
    expect(screen.getByText(/Review transactions/)).toBeInTheDocument();
    expect(screen.getByText(/Create a budget/)).toBeInTheDocument();
    expect(screen.getByText(/Create a goal/)).toBeInTheDocument();
    expect(screen.getByText(/View insights/)).toBeInTheDocument();
  });

  it('calls onDone when "Go to Dashboard" is clicked', () => {
    const onDone = vi.fn();
    render(<MemoryRouter><SuccessScreen onDone={onDone} /></MemoryRouter>);
    fireEvent.click(screen.getByRole('button', { name: 'Go to Dashboard' }));
    expect(onDone).toHaveBeenCalled();
  });

  it('calls onDone when "Import Statement" (the primary CTA) is clicked', () => {
    const onDone = vi.fn();
    render(<MemoryRouter><SuccessScreen onDone={onDone} /></MemoryRouter>);
    fireEvent.click(screen.getByRole('button', { name: 'Import Statement' }));
    expect(onDone).toHaveBeenCalled();
  });

  it('calls onDone when "Connect Account" is clicked', () => {
    const onDone = vi.fn();
    render(<MemoryRouter><SuccessScreen onDone={onDone} /></MemoryRouter>);
    fireEvent.click(screen.getByRole('button', { name: 'Connect Account' }));
    expect(onDone).toHaveBeenCalled();
  });

  // Bug fix regression test: navigate() used to fire in the same tick as onDone(), not sequenced
  // after it. Since the real onDone (OnboardingFlow's finishOnboarding) is async and awaits
  // onboardingApi.complete() before flipping onboardingCompleted, navigating first meant
  // ProtectedRoute -- which gates on that flag, not the URL -- still rendered onboarding's own
  // Success screen at the destination route until the completion call resolved. Import
  // Statement/Connect Account must not navigate until onDone's promise settles.
  it('waits for onDone to resolve before navigating to the destination route', async () => {
    let resolveOnDone: () => void = () => {};
    const onDone = vi.fn(() => new Promise<void>((resolve) => { resolveOnDone = resolve; }));

    render(
      <MemoryRouter initialEntries={['/success']}>
        <Routes>
          <Route path="/success" element={<SuccessScreen onDone={onDone} />} />
          <Route path="/app/import" element={<div>Import page</div>} />
        </Routes>
      </MemoryRouter>
    );

    fireEvent.click(screen.getByRole('button', { name: 'Import Statement' }));
    expect(onDone).toHaveBeenCalled();
    // Still on /success -- the destination route must not have rendered yet.
    expect(screen.queryByText('Import page')).not.toBeInTheDocument();

    await act(async () => {
      resolveOnDone();
      await Promise.resolve();
    });

    expect(await screen.findByText('Import page')).toBeInTheDocument();
  });
});
