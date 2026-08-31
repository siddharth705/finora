import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import BillingHistory from './BillingHistory';
import { billingApi } from '../api/endpoints';
import type { BillingHistoryEntry } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  billingApi: { history: vi.fn() },
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <BillingHistory />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function entry(overrides: Partial<BillingHistoryEntry> = {}): BillingHistoryEntry {
  return {
    id: 'payment-1',
    amount: 499,
    currency: 'INR',
    provider: 'RAZORPAY',
    status: 'SUCCESS',
    createdAt: '2026-08-20T10:00:00Z',
    ...overrides,
  };
}

describe('BillingHistory', () => {
  beforeEach(() => {
    vi.mocked(billingApi.history).mockReset();
  });

  // D-28 PR4-B: this is the only real state the page can be in today -- no payment gateway
  // exists yet, so every user's payments table is genuinely empty, not just empty in this test.
  it('shows the empty state when there is no billing history', async () => {
    vi.mocked(billingApi.history).mockResolvedValue([]);
    renderPage();

    expect(await screen.findByText(/no billing history yet/i)).toBeInTheDocument();
  });

  it('renders a payment row once one exists', async () => {
    vi.mocked(billingApi.history).mockResolvedValue([entry()]);
    renderPage();

    expect(await screen.findByText('₹499')).toBeInTheDocument();
    expect(screen.getByText(/RAZORPAY/)).toBeInTheDocument();
    expect(screen.getByText('Paid')).toBeInTheDocument();
    expect(screen.queryByText(/no billing history yet/i)).not.toBeInTheDocument();
  });

  it('labels a failed payment distinctly from a successful one', async () => {
    vi.mocked(billingApi.history).mockResolvedValue([entry({ id: 'payment-2', status: 'FAILED' })]);
    renderPage();

    expect(await screen.findByText('Failed')).toBeInTheDocument();
  });
});
