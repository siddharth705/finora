import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { TourOverlay } from './TourOverlay';
import type { TourStep } from './tourSteps';

const STEPS: TourStep[] = [
  { targetSelector: '[data-tour="a"]', title: 'Step A', body: 'Body A' },
  { targetSelector: '[data-tour="b"]', title: 'Step B', body: 'Body B' },
];

function renderWithTargets(steps: TourStep[], onFinish = vi.fn(), onSkip = vi.fn()) {
  document.body.innerHTML = '<div data-tour="a"></div><div data-tour="b"></div>';
  return render(<TourOverlay steps={steps} onFinish={onFinish} onSkip={onSkip} />);
}

describe('TourOverlay', () => {
  it('shows the first step title on mount', () => {
    renderWithTargets(STEPS);
    expect(screen.getByText('Step A')).toBeInTheDocument();
  });

  it('advances to the next step on Next', () => {
    renderWithTargets(STEPS);
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    expect(screen.getByText('Step B')).toBeInTheDocument();
  });

  it('calls onFinish after Next on the last step', () => {
    const onFinish = vi.fn();
    renderWithTargets(STEPS, onFinish);
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    fireEvent.click(screen.getByRole('button', { name: 'Finish' }));
    expect(onFinish).toHaveBeenCalled();
  });

  it('calls onSkip from Skip at any step', () => {
    const onSkip = vi.fn();
    renderWithTargets(STEPS, vi.fn(), onSkip);
    fireEvent.click(screen.getByText('Skip'));
    expect(onSkip).toHaveBeenCalled();
  });

  it('goes back to the previous step on Back', () => {
    renderWithTargets(STEPS);
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    fireEvent.click(screen.getByRole('button', { name: 'Back' }));
    expect(screen.getByText('Step A')).toBeInTheDocument();
  });

  it('does not show a Back button on the first step', () => {
    renderWithTargets(STEPS);
    expect(screen.queryByRole('button', { name: 'Back' })).not.toBeInTheDocument();
  });
});
