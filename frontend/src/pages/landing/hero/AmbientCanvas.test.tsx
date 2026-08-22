import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('./webglSupport', () => ({ isWebglAvailable: vi.fn() }));
vi.mock('./useIsDesktop', () => ({ useIsDesktop: vi.fn() }));
vi.mock('./AmbientScene', () => ({
  AmbientScene: () => <div data-testid="ambient-scene-stub" />,
}));
// framer-motion's real useReducedMotion caches its result in a module-level singleton
// (motion-dom's `prefersReducedMotion.current`, set once via a matchMedia listener registered on
// first use) -- reassigning window.matchMedia per test, as the rest of this suite does for other
// hooks, does NOT reliably re-trigger it once that singleton has resolved once in this worker
// process. Mocking the hook directly sidesteps that entirely.
vi.mock('framer-motion', async (importOriginal) => {
  const actual = await importOriginal<typeof import('framer-motion')>();
  return { ...actual, useReducedMotion: vi.fn() };
});

import { useReducedMotion } from 'framer-motion';
import { isWebglAvailable } from './webglSupport';
import { useIsDesktop } from './useIsDesktop';
import { AmbientCanvas } from './AmbientCanvas';

describe('AmbientCanvas', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('renders the ambient scene when desktop + WebGL + motion are all available', async () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    vi.mocked(isWebglAvailable).mockReturnValue(true);
    vi.mocked(useIsDesktop).mockReturnValue(true);

    render(<AmbientCanvas />);
    expect(await screen.findByTestId('ambient-scene-stub')).toBeInTheDocument();
  });

  it('renders a static gradient fallback, not the scene, when WebGL is unavailable', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    vi.mocked(isWebglAvailable).mockReturnValue(false);
    vi.mocked(useIsDesktop).mockReturnValue(true);

    render(<AmbientCanvas />);
    expect(screen.queryByTestId('ambient-scene-stub')).not.toBeInTheDocument();
    expect(screen.getByTestId('ambient-fallback-gradient')).toBeInTheDocument();
  });

  it('renders nothing on mobile, even with WebGL and motion available', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    vi.mocked(isWebglAvailable).mockReturnValue(true);
    vi.mocked(useIsDesktop).mockReturnValue(false);

    const { container } = render(<AmbientCanvas />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing when the user prefers reduced motion, even with WebGL and desktop available', () => {
    vi.mocked(useReducedMotion).mockReturnValue(true);
    vi.mocked(isWebglAvailable).mockReturnValue(true);
    vi.mocked(useIsDesktop).mockReturnValue(true);

    const { container } = render(<AmbientCanvas />);
    expect(container).toBeEmptyDOMElement();
  });
});
