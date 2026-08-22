import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('framer-motion', async (importOriginal) => {
  const actual = await importOriginal<typeof import('framer-motion')>();
  return { ...actual, useReducedMotion: vi.fn() };
});

import { useReducedMotion } from 'framer-motion';
import { hero, heroScore, heroIntelligence } from './landing-config';
import { Hero } from './Hero';

function renderHero() {
  return render(
    <MemoryRouter>
      <Hero />
    </MemoryRouter>
  );
}

describe('Hero', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('renders the existing headline, blurb and CTAs unchanged', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    renderHero();
    expect(screen.getByText(hero.headline)).toBeInTheDocument();
    expect(screen.getByText(hero.headlineAccent)).toBeInTheDocument();
    expect(screen.getByText(hero.blurb)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: new RegExp(hero.primaryCta) })).toBeInTheDocument();
  });

  it('renders the score ring and intelligence scan', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    renderHero();
    expect(screen.getByText(String(heroScore.value))).toBeInTheDocument();
    expect(screen.getByText(heroIntelligence.heading)).toBeInTheDocument();
  });

  it('under prefers-reduced-motion, renders the same final content and never mounts AmbientCanvas', () => {
    vi.mocked(useReducedMotion).mockReturnValue(true);
    const { container } = renderHero();

    // Content is still all there -- reduced motion changes HOW it appears, never WHAT appears.
    expect(screen.getByText(hero.headline)).toBeInTheDocument();
    expect(screen.getByText(String(heroScore.value))).toBeInTheDocument();

    // AmbientCanvas's own gate returns null under reduced motion (see AmbientCanvas.test.tsx);
    // this asserts that behavior actually reaches the page, not just the unit in isolation --
    // jsdom has no real WebGL either way, but the canvas element itself would still appear if the
    // gate were bypassed.
    expect(container.querySelector('canvas')).not.toBeInTheDocument();
  });
});
