import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { mockMatchMedia } from '../../../test/mockMatchMedia';

vi.mock('framer-motion', async (importOriginal) => {
  const actual = await importOriginal<typeof import('framer-motion')>();
  return { ...actual, useReducedMotion: vi.fn() };
});

import { useReducedMotion } from 'framer-motion';
import { FloatingDashboardCard } from './FloatingDashboardCard';

describe('FloatingDashboardCard', () => {
  let restore: (() => void) | undefined;

  afterEach(() => {
    restore?.();
    restore = undefined;
    vi.clearAllMocks();
  });

  it('renders the real DashboardMock content, not a placeholder', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    render(<FloatingDashboardCard />);
    // DashboardMock's own aria-label for level="simple" -- see DashboardMock.tsx's LEVEL_LABEL.
    expect(
      screen.getByRole('img', {
        name: /The Fynora dashboard showing income, expenses, savings and cash flow/,
      })
    ).toBeInTheDocument();
  });

  it('renders the same dashboard content under prefers-reduced-motion', () => {
    vi.mocked(useReducedMotion).mockReturnValue(true);
    render(<FloatingDashboardCard />);
    expect(
      screen.getByRole('img', {
        name: /The Fynora dashboard showing income, expenses, savings and cash flow/,
      })
    ).toBeInTheDocument();
  });

  it('renders the same dashboard content below the desktop breakpoint (mobile fallback path)', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    restore = mockMatchMedia({ '(min-width: 768px)': false });
    render(<FloatingDashboardCard />);
    expect(
      screen.getByRole('img', {
        name: /The Fynora dashboard showing income, expenses, savings and cash flow/,
      })
    ).toBeInTheDocument();
  });
});
