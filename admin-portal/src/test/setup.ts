import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';

// See finora/frontend/src/test/setup.ts's comment on this same hook -- without `test.globals:
// true`, Testing Library's automatic cleanup never self-registers, so every rendered test's DOM
// would otherwise accumulate in document.body for the rest of the file.
afterEach(() => {
  cleanup();
});
