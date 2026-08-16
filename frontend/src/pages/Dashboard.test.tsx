import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Dashboard from './Dashboard';
import { AuthProvider } from '../context/AuthContext';
import {
  dashboardApi, accountsApi, transactionsApi, goalsApi, insightsApi, userApi, budgetsApi, reportsApi,
} from '../api/endpoints';
import type { DashboardSummary } from '../types';

// Dashboard had no prior test file -- this covers only what this change adds (the Financial
// Health Score card, D-19 Step 1), not the whole page. DashboardService has always computed and
// sent healthScore/healthLabel/healthBreakdown; nothing rendered them until now.
vi.mock('../api/endpoints', () => ({
  dashboardApi: { summary: vi.fn() },
  accountsApi: { list: vi.fn() },
  transactionsApi: { search: vi.fn() },
  goalsApi: { list: vi.fn() },
  insightsApi: { get: vi.fn() },
  userApi: { get: vi.fn() },
  budgetsApi: { list: vi.fn() },
  reportsApi: { availableMonths: vi.fn(), forMonth: vi.fn() },
}));

function summary(overrides: Partial<DashboardSummary> = {}): DashboardSummary {
  return {
    currentBalance: 50000,
    totalAssets: 60000,
    totalLiabilities: 10000,
    netWorth: 50000,
    monthlyIncome: 80000,
    monthlyExpense: 45000,
    netCashFlow: 35000,
    savingsRatePct: 43.75,
    incomeDeltaPct: null,
    expenseDeltaPct: null,
    netDeltaPct: null,
    healthScore: 82,
    healthLabel: 'Excellent',
    healthBreakdown: {
      'Savings Rate': 83,
      'Debt Utilization': 100,
      'Emergency Fund': 70,
      'Spend Consistency': 50,
      'Cash Flow Stability': 80,
    },
    spendByCategory: {},
    notifications: [],
    reportingMonth: '2026-08',
    reportingMonthIsCurrent: true,
    ...overrides,
  };
}

function renderDashboard() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <MemoryRouter>
          <Dashboard />
        </MemoryRouter>
      </AuthProvider>
    </QueryClientProvider>
  );
}

describe('Dashboard — Financial Health Score', () => {
  beforeEach(() => {
    vi.mocked(dashboardApi.summary).mockReset().mockResolvedValue(summary());
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(transactionsApi.search).mockReset().mockResolvedValue({
      content: [], page: 0, size: 4, totalElements: 0, totalPages: 0,
    });
    vi.mocked(goalsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(insightsApi.get).mockReset().mockResolvedValue({ sentences: [], movers: [] });
    vi.mocked(userApi.get).mockReset().mockResolvedValue({
      email: 'amy@example.test', fullName: 'Amy Santiago', lowBalanceThreshold: 2000,
      theme: 'system', timezone: 'Asia/Kolkata', phoneNumber: '+919876500000',
      phoneVerified: true, createdAt: '2026-01-01T00:00:00Z', passwordChangedAt: null,
    });
    vi.mocked(budgetsApi.list).mockReset().mockResolvedValue([]);
    vi.mocked(reportsApi.availableMonths).mockReset().mockResolvedValue(['2026-08']);
    vi.mocked(reportsApi.forMonth).mockReset().mockResolvedValue({
      month: '2026-08', income: 80000, expense: 45000, categories: [],
    });
  });

  it('shows the score and label the backend already computed', async () => {
    renderDashboard();

    expect(await screen.findByText('Financial Health Score')).toBeInTheDocument();
    expect(screen.getByText('82')).toBeInTheDocument();
    expect(screen.getByText('Excellent')).toBeInTheDocument();
  });

  it('shows every component of the breakdown with its own percentage', async () => {
    renderDashboard();

    const heading = await screen.findByText('Financial Health Score');
    // "Savings Rate" is also a KPI tile label elsewhere on the page -- scope to the health card
    // itself (the heading's own section) rather than the whole document.
    const card = within(heading.closest('div.bg-card') as HTMLElement);
    expect(card.getByText('Savings Rate')).toBeInTheDocument();
    expect(card.getByText('Debt Utilization')).toBeInTheDocument();
    expect(card.getByText('Emergency Fund')).toBeInTheDocument();
    expect(card.getByText('Spend Consistency')).toBeInTheDocument();
    expect(card.getByText('Cash Flow Stability')).toBeInTheDocument();
    expect(card.getByText('100%')).toBeInTheDocument(); // Debt Utilization
    expect(card.getByText('50%')).toBeInTheDocument(); // Spend Consistency
  });

  it('reflects a low score honestly rather than always looking healthy', async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue(summary({
      healthScore: 28, healthLabel: 'Needs Attention',
      healthBreakdown: { 'Savings Rate': 10, 'Debt Utilization': 20, 'Emergency Fund': 15, 'Spend Consistency': 40, 'Cash Flow Stability': 35 },
    }));
    renderDashboard();

    expect(await screen.findByText('28')).toBeInTheDocument();
    expect(screen.getByText('Needs Attention')).toBeInTheDocument();
  });
});
