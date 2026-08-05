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
