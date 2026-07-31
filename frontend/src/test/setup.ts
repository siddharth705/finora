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
