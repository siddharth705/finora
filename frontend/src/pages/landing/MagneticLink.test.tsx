import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('framer-motion', async (importOriginal) => {
  const actual = await importOriginal<typeof import('framer-motion')>();
  return { ...actual, useReducedMotion: vi.fn() };
});

import { useReducedMotion } from 'framer-motion';
import { MagneticLink, MagneticAnchor } from './MagneticLink';

describe('MagneticLink', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('renders a real, navigable anchor with the exact className preserved', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    render(
      <MemoryRouter>
        <MagneticLink to="/register" className="m-btn m-btn-primary w-full">
          Start free
        </MagneticLink>
      </MemoryRouter>
    );
    const link = screen.getByRole('link', { name: 'Start free' });
    expect(link.tagName).toBe('A');
    expect(link).toHaveAttribute('href', '/register');
    expect(link).toHaveClass('m-btn', 'm-btn-primary', 'w-full');
  });

  it('introduces no extra DOM wrapper -- the anchor is its own top-level element', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    const { container } = render(
      <MemoryRouter>
        <MagneticLink to="/register" className="m-btn">Start free</MagneticLink>
      </MemoryRouter>
    );
    expect(container.firstElementChild?.tagName).toBe('A');
  });

  it('behaves identically under prefers-reduced-motion -- still a real navigable anchor', () => {
    vi.mocked(useReducedMotion).mockReturnValue(true);
    render(
      <MemoryRouter>
        <MagneticLink to="/register" className="m-btn">Start free</MagneticLink>
      </MemoryRouter>
    );
    const link = screen.getByRole('link', { name: 'Start free' });
    expect(link).toHaveAttribute('href', '/register');
  });

  it('MagneticAnchor renders a plain <a> preserving href and className, no wrapper', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    const { container } = render(
      <MagneticAnchor href="#how" className="m-btn m-btn-ghost">
        See how it works
      </MagneticAnchor>
    );
    const link = screen.getByRole('link', { name: 'See how it works' });
    expect(link.tagName).toBe('A');
    expect(link).toHaveAttribute('href', '#how');
    expect(link).toHaveClass('m-btn', 'm-btn-ghost');
    expect(container.firstElementChild?.tagName).toBe('A');
  });
});
