import { canConfirmImport, type ImportGateState } from './importGate';

function state(over: Partial<ImportGateState> = {}): ImportGateState {
  return {
    includedCount: 5,
    outstanding: 0,
    isReimport: false,
    accountChoice: 'existing',
    selectedAccountId: 'acc-1',
    ...over,
  };
}

describe('canConfirmImport', () => {
  it('allows a normal import with rows, no open questions and an account chosen', () => {
    expect(canConfirmImport(state())).toBe(true);
  });

  it('blocks when nothing is ticked', () => {
    expect(canConfirmImport(state({ includedCount: 0 }))).toBe(false);
  });

  it('blocks while a duplicate question is unanswered', () => {
    // The rule the web app enforces too: a duplicate must not be importable by not looking at it.
    expect(canConfirmImport(state({ outstanding: 1 }))).toBe(false);
  });

  it('blocks when "an existing account" is chosen but none is highlighted', () => {
    // Reachable by tapping the "An existing account" chip without then tapping a row. Confirm
    // would otherwise post an empty existingAccountId and get a 400 the user cannot act on.
    expect(canConfirmImport(state({ accountChoice: 'existing', selectedAccountId: '' }))).toBe(false);
  });

  it('allows a new-account import with no account selected', () => {
    expect(canConfirmImport(state({ accountChoice: 'new', selectedAccountId: '' }))).toBe(true);
  });

  it('allows a re-import even with no account selected', () => {
    // THE REGRESSION THIS FILE EXISTS FOR. A re-import is pinned to the account the statement
    // already belongs to and renders no account picker at all, so the account condition has no
    // control on screen that could satisfy it. Before the !isReimport guard, a user who had
    // tapped "An existing account" on an earlier statement without picking a row -- state the
    // screen keeps, since the Import tab stays mounted across tab switches -- then started a
    // re-import from Statement History got a dead Import button and no explanation, with only
    // Cancel (which discards the whole re-import) as a way out.
    expect(
      canConfirmImport(state({ isReimport: true, accountChoice: 'existing', selectedAccountId: '' }))
    ).toBe(true);
  });

  it('still blocks a re-import that has nothing ticked or an open question', () => {
    // The re-import exemption is scoped to the ACCOUNT condition only -- it must not become a
    // blanket bypass of the other two.
    expect(canConfirmImport(state({ isReimport: true, includedCount: 0 }))).toBe(false);
    expect(canConfirmImport(state({ isReimport: true, outstanding: 2 }))).toBe(false);
  });
});
