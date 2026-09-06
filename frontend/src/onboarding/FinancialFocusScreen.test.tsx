import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { FinancialFocusScreen } from './FinancialFocusScreen';

describe('FinancialFocusScreen', () => {
  it('calls onContinue with the selected keys', () => {
    const onContinue = vi.fn();
    render(<FinancialFocusScreen onContinue={onContinue} />);
    fireEvent.click(screen.getByText(/Track my spending/));
    fireEvent.click(screen.getByText(/Reduce debt/));
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    expect(onContinue).toHaveBeenCalledWith(['TRACK_SPENDING', 'REDUCE_DEBT']);
  });

  it('allows continuing with nothing selected', () => {
    const onContinue = vi.fn();
    render(<FinancialFocusScreen onContinue={onContinue} />);
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    expect(onContinue).toHaveBeenCalledWith([]);
  });

  it('selecting "Just exploring" clears every other selection', () => {
    const onContinue = vi.fn();
    render(<FinancialFocusScreen onContinue={onContinue} />);
    fireEvent.click(screen.getByText(/Track my spending/));
    fireEvent.click(screen.getByText(/Just exploring/));
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    expect(onContinue).toHaveBeenCalledWith(['EXPLORING']);
  });

  it('selecting a normal option after "Just exploring" clears the exploring flag', () => {
    const onContinue = vi.fn();
    render(<FinancialFocusScreen onContinue={onContinue} />);
    fireEvent.click(screen.getByText(/Just exploring/));
    fireEvent.click(screen.getByText(/Track my spending/));
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    expect(onContinue).toHaveBeenCalledWith(['TRACK_SPENDING']);
  });
});
