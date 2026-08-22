import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import Landing from './Landing';

describe('Landing — Hero-visibility observer', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('drives overHero via an IntersectionObserver watching the Hero wrapper, rootMargin-adjusted for the nav height', () => {
    // The page already uses IntersectionObserver elsewhere (every section's own scroll-reveal),
    // so this asserts on the SPECIFIC instance this feature creates -- identified by its
    // rootMargin, which no other observer on the page uses -- rather than a raw call count.
    type Options = IntersectionObserverInit | undefined;
    const heroObserveSpy = vi.fn();
    const OriginalIO = window.IntersectionObserver;
    window.IntersectionObserver = vi.fn((callback: IntersectionObserverCallback, options: Options) => {
      const isHeroObserver = options?.rootMargin?.includes('px 0px 0px 0px') && options.rootMargin !== '0px 0px 0px 0px';
      return {
        observe: isHeroObserver ? heroObserveSpy : vi.fn(),
        unobserve: vi.fn(),
        disconnect: vi.fn(),
        takeRecords: () => [],
      } as unknown as IntersectionObserver;
    }) as unknown as typeof IntersectionObserver;

    render(
      <MemoryRouter>
        <Landing />
      </MemoryRouter>
    );

    expect(heroObserveSpy).toHaveBeenCalledTimes(1);

    window.IntersectionObserver = OriginalIO;
  });
});
