import { describe, it, expect } from 'vitest';
import { contextForPath } from './feedbackContext';

describe('contextForPath', () => {
  it.each([
    ['/app', 'DASHBOARD'],
    ['/app/transactions', 'TRANSACTIONS'],
    ['/app/reports', 'REPORTS'],
    ['/app/budgets', 'BUDGETS'],
    ['/app/goals', 'GOALS'],
    ['/app/import', 'IMPORT_FLOW'],
    ['/app/imports/job-1', 'IMPORT_FLOW'],
    ['/app/statements', 'IMPORT_FLOW'],
    ['/app/accounts', 'ACCOUNTS'],
    ['/app/settings', 'SETTINGS'],
    ['/app/settings/gmail/review', 'SETTINGS'],
    ['/app/profile', 'SETTINGS'],
    ['/help', 'HELP'],
  ])('maps %s to %s', (path, expected) => {
    expect(contextForPath(path)).toBe(expected);
  });

  it('falls back to OTHER for a route with no dedicated context (e.g. Investments, Support)', () => {
    expect(contextForPath('/app/investments')).toBe('OTHER');
    expect(contextForPath('/app/support')).toBe('OTHER');
    expect(contextForPath('/app/billing')).toBe('OTHER');
  });
});
