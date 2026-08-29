import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import InsightsExplorer from './InsightsExplorer';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminInsightsExplorerApi } from '../api/endpoints';
import type { InsightsExplorerTrace } from '../types';

vi.mock('../context/ThemeContext', () => ({
  useTheme: () => ({ theme: 'system', resolvedTheme: 'light', setTheme: vi.fn() }),
}));
vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
  adminInsightsExplorerApi: {
    trace: vi.fn(),
  },
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <InsightsExplorer />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function mockAuth(permissions: string[] = ['INSIGHTS_EXPLORER_VIEW']) {
  vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
    hasPermission: (p: string) => permissions.includes(p),
    permissions,
    fullName: 'Ops Admin',
  }));
}

function trace(over: Partial<InsightsExplorerTrace> = {}): InsightsExplorerTrace {
  return {
    userId: '0f8b1c2d-0000-0000-0000-000000000001',
    reportingMonth: '2026-07',
    reportingMonthIsCurrent: true,
    totalSpend: {
      amount: 800,
      categoryCount: 1,
      transactions: [
        {
          transactionId: '0f8b1c2d-0000-0000-0000-000000000002',
          description: 'ZOMATO ORDER',
          rawAmount: 800,
          reportableAmount: 800,
          txnDate: '2026-07-10',
        },
      ],
    },
    topCategory: {
      category: 'Dining',
      amount: 800,
      transactions: [
        {
          transactionId: '0f8b1c2d-0000-0000-0000-000000000002',
          description: 'ZOMATO ORDER',
          rawAmount: 800,
          reportableAmount: 800,
          txnDate: '2026-07-10',
        },
      ],
    },
    topMerchant: {
      merchant: 'Zomato',
      amount: 800,
      transactions: [
        {
          transactionId: '0f8b1c2d-0000-0000-0000-000000000002',
          description: 'ZOMATO ORDER',
          rawAmount: 800,
          reportableAmount: 800,
          txnDate: '2026-07-10',
        },
      ],
    },
    ...over,
  };
}

async function traceFor(id = '0f8b1c2d-0000-0000-0000-000000000001') {
  const user = userEvent.setup();
  renderPage();
  await user.type(screen.getByLabelText('User id'), id);
  await user.click(screen.getByRole('button', { name: /trace/i }));
  return user;
}

beforeEach(() => {
  mockAuth();
  vi.mocked(adminInsightsExplorerApi.trace).mockReset().mockResolvedValue(trace());
});

describe('Insight Explorer — a user\'s dashboard numbers, traced to the transactions behind them', () => {
  it('shows the reporting period, total spend, top category and top merchant', async () => {
    await traceFor();

    expect(await screen.findByText('Reporting period')).toBeInTheDocument();
    expect(screen.getByText('2026-07')).toBeInTheDocument();
    expect(screen.getByText('Total spend')).toBeInTheDocument();
    expect(screen.getByText('Top category')).toBeInTheDocument();
    expect(screen.getByText('Dining')).toBeInTheDocument();
    expect(screen.getByText('Top merchant')).toBeInTheDocument();
    expect(screen.getByText('Zomato')).toBeInTheDocument();
  });

  it('lists the transactions that contributed to each traced number', async () => {
    await traceFor();

    expect(await screen.findAllByText('0f8b1c2d-0000-0000-0000-000000000002')).not.toHaveLength(0);
    expect(screen.getAllByText('ZOMATO ORDER').length).toBeGreaterThan(0);
  });

  it('flags a refund-netted transaction where the raw and reportable amounts differ', async () => {
    vi.mocked(adminInsightsExplorerApi.trace).mockResolvedValue(trace({
      totalSpend: {
        amount: 300,
        categoryCount: 1,
        transactions: [{
          transactionId: '0f8b1c2d-0000-0000-0000-000000000003',
          description: 'ZOMATO ORDER',
          rawAmount: 500,
          reportableAmount: 300,
          txnDate: '2026-07-10',
        }],
      },
    }));
    await traceFor();

    expect(await screen.findByText(/refund netted/i)).toBeInTheDocument();
  });
});

describe('Insight Explorer — no spending data is an answer', () => {
  it('says no spending data rather than rendering empty or zeroed panels', async () => {
    vi.mocked(adminInsightsExplorerApi.trace).mockResolvedValue(trace({
      reportingMonth: null,
      reportingMonthIsCurrent: false,
      totalSpend: null,
      topCategory: null,
      topMerchant: null,
    }));
    await traceFor();

    expect(await screen.findByText(/no spending data/i)).toBeInTheDocument();
  });
});

describe('Insight Explorer — errors', () => {
  it('reports an unknown user id as not found', async () => {
    vi.mocked(adminInsightsExplorerApi.trace).mockRejectedValue(new Error('not found'));
    await traceFor();

    expect(await screen.findByText(/no user with that id/i)).toBeInTheDocument();
  });
});

describe('Insight Explorer — access', () => {
  it('is gated on INSIGHTS_EXPLORER_VIEW', async () => {
    mockAuth([]);
    renderPage();

    expect(screen.queryByRole('button', { name: /trace/i })).not.toBeInTheDocument();
    expect(adminInsightsExplorerApi.trace).not.toHaveBeenCalled();
  });
});
