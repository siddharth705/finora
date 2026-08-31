import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, vi } from 'vitest';
import { cleanup, configure } from '@testing-library/react';

// Testing Library's async utilities -- findBy*, waitFor -- give up after their OWN timeout, which
// defaults to 1000ms and is NOT the same knob as vitest's `testTimeout`. Both configs already set
// testTimeout to 15s reasoning about slow, loaded CI machines; that raised the ceiling on a whole
// test while leaving every individual `await screen.findByRole(...)` bailing out after one second.
//
// Which is what actually broke. On 2026-08-08 `main` went red on two admin-portal tests --
// Diagnostics' "Copy diagnostics" button and UserDetail's "Accounts" heading -- both
// `Unable to find role=...`, while the same two passed locally in 105ms and 297ms. The runner was
// draining ~45 queued jobs from a dependabot batch at the time, and a render that takes 100ms idle
// does not finish inside a second on a machine in that state. Nothing was wrong with either test
// or the code under it.
//
// 5s rather than matching testTimeout's 15s: long enough to absorb a heavily loaded machine, short
// enough that a genuine "this element never appears" still fails with the query's own useful error
// well before vitest kills the whole test with a far less informative one.
configure({ asyncUtilTimeout: 5_000 });

// jsdom doesn't implement matchMedia -- ThemeContext calls it unconditionally (both to read the
// initial OS preference and to subscribe to changes), so without this every test that mounts
// ThemeProvider (App.test.tsx renders the real one) would throw "window.matchMedia is not a
// function" before it even got to the assertion. Same fix as frontend/src/test/setup.ts, ported
// verbatim -- addEventListener/removeEventListener are no-ops since no test here exercises a live
// OS theme change.
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

// jsdom has no ResizeObserver, and recharts' <ResponsiveContainer> (the first charting library
// this repo has needed -- PlatformActivityChart, dashboard redesign PR4) renders nothing at all
// without one: its own effect bails out entirely when `typeof ResizeObserver === 'undefined'`,
// leaving its initial {width: -1, height: -1} in place, which fails its own "acceptable size"
// check and returns null forever. A synchronous stub that reports one fixed size is enough --
// nothing here exercises a real browser resize, only a chart mounting and rendering its data.
if (!window.ResizeObserver) {
  class StubResizeObserver {
    private readonly callback: ResizeObserverCallback;
    constructor(callback: ResizeObserverCallback) {
      this.callback = callback;
    }
    observe(target: Element) {
      this.callback(
        [{ target, contentRect: { width: 400, height: 220 } } as unknown as ResizeObserverEntry],
        this as unknown as ResizeObserver
      );
    }
    unobserve() {}
    disconnect() {}
  }
  window.ResizeObserver = StubResizeObserver as unknown as typeof ResizeObserver;
}

// jsdom does not implement window.prompt: it logs "Not implemented" and returns undefined, which
// is neither the "Cancel" `null` a real browser returns nor a string -- code written for a real
// prompt() call (e.g. `if (reason !== null) mutate(reason.trim())`) crashes on `.trim()` instead of
// reading as cancelled. An empty string is the permissive default: it reads as "OK with no text
// entered," so a test exercising what happens after the admin proceeds doesn't have to know this
// dialog exists. A test asserting the Cancel path should override this explicitly with
// `vi.mocked(window.prompt).mockReturnValueOnce(null)`.
//
// window.confirm used to need the same treatment, until every confirm()-guarded action in this app
// was converted to a custom ConfirmDialog (a real rendered component, not a browser API jsdom has
// to fake) -- no stub needed for it anymore.
beforeEach(() => {
  vi.spyOn(window, 'prompt').mockReturnValue('');
});

// See finora/frontend/src/test/setup.ts's comment on this same hook -- without `test.globals:
// true`, Testing Library's automatic cleanup never self-registers, so every rendered test's DOM
// would otherwise accumulate in document.body for the rest of the file.
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

// Node 22+ ships its own experimental global `localStorage`, functional only when the process is
// started with --localstorage-file. Without that flag the global still EXISTS, as an accessor that
// returns undefined, and since vitest's jsdom environment aliases globalThis to the jsdom window,
// that accessor sits in the slot jsdom's own Storage would have filled. Both `localStorage` and
// `window.localStorage` are then undefined, and every suite that touches storage in a beforeEach
// dies before its first assertion:
//
//   TypeError: Cannot read properties of undefined (reading 'clear')
//
// 36 failures across six files here, surviving a clean `npm install`, while CI stays green because
// .github/workflows/ci.yml pins Node 20. See finora/frontend/src/test/setup.ts, which carries the
// same block for the same reason; keep the two in step.
//
// Guarded on the broken case, so under Node 20 jsdom's real Storage is left untouched. A plain
// in-memory Storage rather than a mock on purpose: these tests assert on what was actually
// persisted, not on which methods got called.
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

  // defineProperty, not assignment: Node's version is an accessor with no setter, so a plain
  // `globalThis.localStorage = ...` is a no-op in sloppy mode and a TypeError under ESM's strict
  // mode. It is configurable, so redefining it is allowed.
  Object.defineProperty(globalThis, 'localStorage', {
    value: memoryStorage as unknown as Storage,
    configurable: true,
    writable: true,
  });
}
