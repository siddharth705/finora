import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';

// @testing-library/react's automatic post-test cleanup only self-registers against a *global*
// afterEach -- since this project doesn't set `test.globals: true` in vitest.config.ts (tests
// import everything explicitly from 'vitest' instead), that auto-registration never fires, and
// each test's rendered DOM would otherwise pile up in document.body across every test in the
// same file (breaking any getBy* query that a later test in the file also happens to match).
afterEach(() => {
  cleanup();
});

// jsdom doesn't implement matchMedia -- ThemeContext calls it unconditionally (both to read the
// initial OS preference and to subscribe to changes), so without this every test that mounts
// ThemeProvider would throw "window.matchMedia is not a function" before it even got to the
// assertion. addEventListener/removeEventListener are no-ops here since no test in this suite
// exercises a live OS theme change; a real listener implementation would need a subscribe list.
if (!window.matchMedia) {
  window.matchMedia = (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  }) as unknown as MediaQueryList;
}

// Same story as matchMedia: jsdom doesn't implement IntersectionObserver, and useScrollReveal
// constructs one unconditionally in an effect. Any test rendering a page that uses it (Landing,
// and every marketing page built on the same hook) died with "IntersectionObserver is not
// defined" thrown from inside React's commit phase -- which reads as a mysterious render crash
// rather than a missing browser API, and is a large part of why none of those pages had a test.
//
// Never reports an intersection, so reveal-on-scroll content stays in its initial state. That is
// the honest default for a zero-height jsdom viewport where nothing is ever actually scrolled
// into view; a test that wants to assert the revealed state should drive the callback itself
// rather than have this pretend everything is visible.
if (!window.IntersectionObserver) {
  class NoopIntersectionObserver implements IntersectionObserver {
    readonly root = null;
    readonly rootMargin = '';
    readonly thresholds: ReadonlyArray<number> = [];
    observe() {}
    unobserve() {}
    disconnect() {}
    takeRecords(): IntersectionObserverEntry[] { return []; }
  }
  window.IntersectionObserver = NoopIntersectionObserver as unknown as typeof IntersectionObserver;
  globalThis.IntersectionObserver = window.IntersectionObserver;
}

// Node 22+ ships its own experimental global `localStorage`, functional only when the process is
// started with --localstorage-file. Without that flag the global still EXISTS, as an accessor that
// returns undefined, and since vitest's jsdom environment aliases globalThis to the jsdom window
// (globalThis === window in here), that accessor sits in the slot jsdom's own Storage would have
// filled. Both `localStorage` and `window.localStorage` are then undefined, and every suite whose
// beforeEach starts with localStorage.clear() dies before its first assertion:
//
//   TypeError: Cannot read properties of undefined (reading 'clear')
//
// Worth being precise about the failure mode, because it does not look like an environment
// problem. It is 33 failures across four unrelated files (client, AuthContext, ThemeContext,
// ChangePasswordModal), it survives a clean `npm install`, and CI stays green throughout, since
// .github/workflows/ci.yml pins Node 20. Whoever upgrades Node first sees a suite that appears to
// have broken itself.
//
// Guarded on the broken case, so under Node 20 jsdom's real Storage is left untouched and this
// block does nothing. A plain in-memory Storage rather than a mock on purpose: these tests assert
// on what was actually persisted (and read it back), not on which methods got called.
if (!globalThis.localStorage) {
  const entries = new Map<string, string>();
  const memoryStorage = {
    get length() {
      return entries.size;
    },
    clear: () => {
      entries.clear();
    },
    getItem: (key: string) => (entries.has(key) ? entries.get(key)! : null),
    key: (index: number) => Array.from(entries.keys())[index] ?? null,
    removeItem: (key: string) => {
      entries.delete(key);
    },
    setItem: (key: string, value: string) => {
      entries.set(String(key), String(value));
    },
  };

  // defineProperty, not assignment: Node's version is an accessor with no setter, so `globalThis
  // .localStorage = ...` is silently a no-op in sloppy mode and a TypeError under ESM's strict
  // mode. It is configurable, so redefining it is allowed.
  Object.defineProperty(globalThis, 'localStorage', {
    value: memoryStorage as unknown as Storage,
    configurable: true,
    writable: true,
  });
}
