import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('framer-motion', async (importOriginal) => {
  const actual = await importOriginal<typeof import('framer-motion')>();
  return { ...actual, useReducedMotion: vi.fn() };
});

import { useReducedMotion } from 'framer-motion';
import { heroBadges } from '../landing-config';
import { FloatingBadges } from './FloatingBadges';

describe('FloatingBadges', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('renders every badge label from landing-config', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    render(<FloatingBadges />);
    heroBadges.forEach((badge) => {
      expect(screen.getByText(badge.label)).toBeInTheDocument();
    });
  });

  it('still renders every badge label under prefers-reduced-motion', () => {
    vi.mocked(useReducedMotion).mockReturnValue(true);
    render(<FloatingBadges />);
    heroBadges.forEach((badge) => {
      expect(screen.getByText(badge.label)).toBeInTheDocument();
    });
  });
});
