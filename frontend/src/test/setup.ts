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
