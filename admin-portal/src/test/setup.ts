import '@testing-library/jest-dom/vitest';
import { afterEach, beforeEach, vi } from 'vitest';
import { cleanup } from '@testing-library/react';

// jsdom does not implement window.confirm: it logs "Not implemented" and returns undefined, which
// is falsy, so ANY confirm-guarded action is untestable by default -- the guard always reads as
// "cancelled" and the action never runs. Defaulting to true makes the confirm dialog transparent
// to tests that are exercising what happens AFTER the user agrees, which is what almost every
// test of a destructive action is actually about.
//
// A test that wants to assert the guard itself exists should override this explicitly:
//
//   vi.mocked(window.confirm).mockReturnValueOnce(false);
//
// and then assert the mutation did NOT fire. Stated here because the default is deliberately
// permissive, and a permissive default can hide a missing confirmation if nobody knows to look.
beforeEach(() => {
  vi.spyOn(window, 'confirm').mockReturnValue(true);
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
