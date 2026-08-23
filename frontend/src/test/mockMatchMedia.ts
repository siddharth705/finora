import { vi } from 'vitest';

/**
 * Swaps window.matchMedia for one that resolves each query against `queryToMatches`, defaulting
 * any unlisted query to false. src/test/setup.ts installs a default no-op matchMedia globally
 * (always matches: false); several hero tests need to flip a *specific* query -- prefers-reduced-
 * motion, min-width -- per test, which this makes possible without touching the global default.
 */
export function mockMatchMedia(queryToMatches: Record<string, boolean>): () => void {
  const original = window.matchMedia;
  window.matchMedia = vi.fn((query: string) => ({
    matches: queryToMatches[query] ?? false,
    media: query,
    onchange: null,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: vi.fn(() => false),
  })) as unknown as typeof window.matchMedia;
  return () => {
    window.matchMedia = original;
  };
}
