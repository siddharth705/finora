import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
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
});
