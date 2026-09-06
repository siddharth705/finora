import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { WelcomeScreen } from './WelcomeScreen';

describe('WelcomeScreen', () => {
  it('calls onStart when "Start Setup" is clicked', () => {
    const onStart = vi.fn();
    render(<WelcomeScreen onStart={onStart} onSkip={vi.fn()} />);
    fireEvent.click(screen.getByRole('button', { name: 'Start Setup' }));
    expect(onStart).toHaveBeenCalled();
  });

  it('calls onSkip when "Skip for Now" is clicked', () => {
    const onSkip = vi.fn();
    render(<WelcomeScreen onStart={vi.fn()} onSkip={onSkip} />);
    fireEvent.click(screen.getByRole('button', { name: 'Skip for Now' }));
    expect(onSkip).toHaveBeenCalled();
  });
});
