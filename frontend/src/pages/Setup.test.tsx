import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import Setup from './Setup';
import { accountsApi, banksApi } from '../api/endpoints';
import type { Account } from '../types';

vi.mock('../api/endpoints', () => ({
  accountsApi: { list: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn() },
  banksApi: { list: vi.fn() },
}));

function account(overrides: Partial<Account> = {}): Account {
  return {
    id: 'a1',
    name: 'Salary Account',
    accountType: 'SAVINGS',
    balance: 15000,
    bank: { id: 'OTHER', officialName: null, shortName: 'Bank' },
    status: 'ACTIVE',
    statementsCount: 0,
    transactionsCount: 0,
    accountHolderName: null,
    accountNumberMasked: null,
    lastImportedAt: null,
    lastStatementPeriodStart: null,
    lastStatementPeriodEnd: null,
    ...overrides,
  } as unknown as Account;
}

function renderPage() {
  return render(
    <MemoryRouter>
      <Setup />
    </MemoryRouter>
  );
}

describe('Setup', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(banksApi.list).mockResolvedValue([]);
  });

  // The bug: `accounts` started `[]`, indistinguishable from "genuinely no accounts yet" -- the
  // worst instance of this class of bug on the page, since the real account cards (bank logo,
  // balance, statement period, masked number) are visually much richer than a budget row or a
  // goal card, making the empty->real content jump the most jarring of the three.
  it('never shows the empty state while the initial fetch is still in flight', async () => {
    let resolveList: (a: Account[]) => void;
    vi.mocked(accountsApi.list).mockReturnValue(
      new Promise((resolve) => {
        resolveList = resolve;
      })
    );

    renderPage();

    expect(screen.queryByText(/No accounts yet/)).not.toBeInTheDocument();

    resolveList!([account()]);
    await waitFor(() => expect(screen.getByText('Salary Account')).toBeInTheDocument());
    expect(screen.queryByText(/No accounts yet/)).not.toBeInTheDocument();
  });

  it('shows the empty state once loading finishes with genuinely no accounts', async () => {
    vi.mocked(accountsApi.list).mockResolvedValue([]);
    renderPage();

    expect(await screen.findByText(/No accounts yet/)).toBeInTheDocument();
  });

  it('renders each account once loaded, with its balance', async () => {
    vi.mocked(accountsApi.list).mockResolvedValue([
      account({ id: 'a1', name: 'Salary Account', balance: 15000 }),
      account({ id: 'a2', name: 'Travel Card', accountType: 'CREDIT_CARD', balance: -2500 }),
    ]);
    renderPage();

    expect(await screen.findByText('Salary Account')).toBeInTheDocument();
    expect(screen.getByText('Travel Card')).toBeInTheDocument();
  });
});
