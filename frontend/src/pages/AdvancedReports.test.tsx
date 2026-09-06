import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import AdvancedReports from './AdvancedReports';
import { analyticsApi, entitlementsApi, reportsApi } from '../api/endpoints';
import type { EntitlementsDto } from '../api/endpoints';

// jsdom has no canvas, so react-chartjs-2's <Line>/<Bar> crash the whole React root on their
// first data update -- same gotcha Dashboard.test.tsx and Investments.test.tsx already document
// and work around. The charts aren't under test here; the entitlement gate and the page's own
// data plumbing are.
vi.mock('react-chartjs-2', () => ({
  Line: () => <div data-testid="spend-trend-chart" />,
  Bar: () => <div data-testid="bar-chart" />,
}));

vi.mock('../api/endpoints', () => ({
  entitlementsApi: { mine: vi.fn() },
  analyticsApi: {
    topMerchants: vi.fn(),
    topCategories: vi.fn(),
    trend: vi.fn(),
    categoryConfidence: vi.fn(),
    learningGrowth: vi.fn(),
  },
  reportsApi: { availableMonths: vi.fn() },
}));

function entitlements(overrides: Partial<EntitlementsDto> = {}): EntitlementsDto {
  return { planCode: 'FREE', planName: 'Free', features: {}, ...overrides };
}

function pending<T>(): Promise<T> {
  return new Promise<T>(() => {});
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AdvancedReports />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('AdvancedReports', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Never resolving unless a test says otherwise -- a Free-plan test should never need these
    // to resolve at all, since the gate keeps AdvancedReportsContent from ever mounting.
    vi.mocked(analyticsApi.topMerchants).mockReturnValue(pending());
    vi.mocked(analyticsApi.topCategories).mockReturnValue(pending());
    vi.mocked(analyticsApi.trend).mockReturnValue(pending());
    vi.mocked(analyticsApi.categoryConfidence).mockReturnValue(pending());
    vi.mocked(analyticsApi.learningGrowth).mockReturnValue(pending());
    vi.mocked(reportsApi.availableMonths).mockReturnValue(pending());
  });

  it('always shows the page title, even before the entitlement check resolves', () => {
    vi.mocked(entitlementsApi.mine).mockReturnValue(pending());

    renderPage();

    expect(screen.getByRole('heading', { name: /Advanced Reports/ })).toBeInTheDocument();
  });

  it('shows the upgrade prompt, not the report content, for a Free-plan user', async () => {
    vi.mocked(entitlementsApi.mine).mockResolvedValue(entitlements({ features: {} }));

    renderPage();

    expect(await screen.findByText(/Plus & Premium feature/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /View plans/ })).toHaveAttribute('href', '/app/billing');
    // The gated content never mounts for a denied user -- none of its queries should ever fire.
    expect(analyticsApi.topMerchants).not.toHaveBeenCalled();
    expect(screen.queryByText('Top Merchants')).not.toBeInTheDocument();
  });

  it('shows the upgrade prompt for a user with no subscription at all (fails closed)', async () => {
    vi.mocked(entitlementsApi.mine).mockResolvedValue({ planCode: null, planName: null, features: {} });

    renderPage();

    expect(await screen.findByText(/Plus & Premium feature/)).toBeInTheDocument();
  });

  it('renders real report data for an entitled (Plus/Premium) user', async () => {
    vi.mocked(entitlementsApi.mine).mockResolvedValue(entitlements({ planCode: 'PLUS', features: { ADVANCED_REPORTS: true } }));
    vi.mocked(reportsApi.availableMonths).mockResolvedValue(['2026-07', '2026-08']);
    vi.mocked(analyticsApi.topMerchants).mockResolvedValue([
      { merchantId: 'm1', merchantName: 'Swiggy', totalSpend: 4500, transactionCount: 12 },
    ]);
    vi.mocked(analyticsApi.topCategories).mockResolvedValue([
      { categoryId: 'c1', categoryName: 'Food', totalSpend: 6000, transactionCount: 15 },
    ]);
    vi.mocked(analyticsApi.trend).mockResolvedValue([{ month: '2026-08', totalSpend: 12000 }]);
    vi.mocked(analyticsApi.categoryConfidence).mockResolvedValue([{ category: 'Food', avgConfidence: 88, merchantCount: 4 }]);
    vi.mocked(analyticsApi.learningGrowth).mockResolvedValue([{ month: '2026-08', learnedCount: 10, correctedCount: 2 }]);

    renderPage();

    expect(await screen.findByText('Swiggy')).toBeInTheDocument();
    expect(screen.getByText('Food')).toBeInTheDocument();
    expect(screen.queryByText(/Plus & Premium feature/)).not.toBeInTheDocument();
  });

  it('shows an empty state per section when an entitled user has no data yet', async () => {
    vi.mocked(entitlementsApi.mine).mockResolvedValue(entitlements({ planCode: 'PREMIUM', features: { ADVANCED_REPORTS: true } }));
    vi.mocked(reportsApi.availableMonths).mockResolvedValue([]);
    vi.mocked(analyticsApi.topMerchants).mockResolvedValue([]);
    vi.mocked(analyticsApi.topCategories).mockResolvedValue([]);
    vi.mocked(analyticsApi.trend).mockResolvedValue([]);
    vi.mocked(analyticsApi.categoryConfidence).mockResolvedValue([]);
    vi.mocked(analyticsApi.learningGrowth).mockResolvedValue([]);

    renderPage();

    expect(await screen.findByText('No merchant spend yet')).toBeInTheDocument();
    expect(screen.getByText('No categorized spend yet')).toBeInTheDocument();
  });
});
