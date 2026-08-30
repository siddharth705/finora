import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AccountsSection } from './AccountsSection';
import { adminAccountsApi } from '../../api/endpoints';
import type { AccountDto, CoverageDto } from '../../types';

vi.mock('../../api/endpoints', () => ({
  adminAccountsApi: { list: vi.fn(), create: vi.fn(), update: vi.fn(), delete: vi.fn(), coverage: vi.fn() },
  banksApi: { search: vi.fn().mockResolvedValue([]) },
}));

let mockHasPermission: (permission: string) => boolean;
vi.mock('../../context/AdminAuthContext', () => ({
  useAdminAuth: () => ({ hasPermission: mockHasPermission }),
}));

const ACCOUNT: AccountDto = {
  id: 'acc-1', name: 'HDFC Savings', accountType: 'SAVINGS', balance: 50000, creditLimit: null,
  dueDate: null, investmentKind: null, accountHolderName: null, accountNumberMasked: null,
  branchName: null, ifscCode: null, bank: { id: 'bank-1', shortName: 'HDFC', initials: 'HD', colorHex: '#000' } as any,
  lastImportedAt: null, lastStatementPeriodStart: null, lastStatementPeriodEnd: null,
  statementsCount: 2, transactionsCount: 10, status: 'ACTIVE',
};

const COVERAGE: CoverageDto = {
  accountId: 'acc-1', coverageStatus: 'HAS_GAPS', coveredDays: 62, missingDays: 30, coveragePercentage: 67.4,
  hasGaps: true, hasOverlaps: false, hasNonStandardPeriods: false, hasDuplicatePeriods: false,
  segments: [], gaps: [{ gapStart: '2026-06-01', gapEnd: '2026-06-30', daysMissing: 30, delta: 488000 }],
  overlaps: [],
};

function renderSection() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <AccountsSection userId="user-1" />
    </QueryClientProvider>
  );
}

/**
 * Phase 1 of docs/proposals/statement-continuity-and-coverage-integrity-proposal.md (§0.14) --
 * the admin coverage view. Gated on PLATFORM_DIAGNOSTICS_VIEW, same permission
 * AdminImportTraceController's own trace tools use, deliberately separate from the
 * ACCOUNT_CREATE/UPDATE/DELETE permissions the rest of this section already checks.
 */
describe('AccountsSection coverage panel', () => {
  it('does not show a Coverage toggle without PLATFORM_DIAGNOSTICS_VIEW', async () => {
    mockHasPermission = () => false;
    vi.mocked(adminAccountsApi.list).mockResolvedValue([ACCOUNT]);

    renderSection();

    await waitFor(() => expect(screen.getByText('HDFC Savings')).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: /coverage/i })).not.toBeInTheDocument();
  });

  it('fetches and renders the coverage report when the toggle is expanded', async () => {
    mockHasPermission = () => true;
    vi.mocked(adminAccountsApi.list).mockResolvedValue([ACCOUNT]);
    vi.mocked(adminAccountsApi.coverage).mockResolvedValue(COVERAGE);

    renderSection();
    await waitFor(() => expect(screen.getByText('HDFC Savings')).toBeInTheDocument());

    await userEvent.click(screen.getByRole('button', { name: /coverage/i }));

    await waitFor(() => expect(adminAccountsApi.coverage).toHaveBeenCalledWith('acc-1'));
    expect(await screen.findByText('HAS_GAPS')).toBeInTheDocument();
    expect(screen.getByText(/Missing 2026-06-01 to 2026-06-30/)).toBeInTheDocument();
  });

  it('does not fetch coverage until the toggle is expanded', async () => {
    mockHasPermission = () => true;
    vi.mocked(adminAccountsApi.list).mockResolvedValue([ACCOUNT]);
    vi.mocked(adminAccountsApi.coverage).mockResolvedValue(COVERAGE);

    renderSection();
    await waitFor(() => expect(screen.getByText('HDFC Savings')).toBeInTheDocument());

    expect(adminAccountsApi.coverage).not.toHaveBeenCalled();
  });
});
