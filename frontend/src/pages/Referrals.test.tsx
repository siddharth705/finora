import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Referrals from './Referrals';
import { referralsApi } from '../api/endpoints';
import type { MyReferralEntry } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  referralsApi: { myCode: vi.fn(), mine: vi.fn() },
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <Referrals />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function entry(overrides: Partial<MyReferralEntry> = {}): MyReferralEntry {
  return {
    referralId: 'referral-1',
    referredUserFullName: 'Jane Doe',
    status: 'REGISTERED',
    reward: null,
    createdAt: '2026-08-20T10:00:00Z',
    ...overrides,
  };
}

describe('Referrals', () => {
  beforeEach(() => {
    vi.mocked(referralsApi.myCode).mockReset().mockResolvedValue({ code: 'ABCD1234' });
    vi.mocked(referralsApi.mine).mockReset();
  });

  it('shows the empty state and a zero balance when nothing has happened yet', async () => {
    vi.mocked(referralsApi.mine).mockResolvedValue({ referrals: [], walletBalance: 0 });
    renderPage();

    expect(await screen.findByText(/no referrals yet/i)).toBeInTheDocument();
    expect(screen.getByText('₹0')).toBeInTheDocument();
  });

  it("renders the user's own referral link once the code loads", async () => {
    vi.mocked(referralsApi.mine).mockResolvedValue({ referrals: [], walletBalance: 0 });
    renderPage();

    const input = await screen.findByDisplayValue(/\/register\?ref=ABCD1234$/);
    expect(input).toBeInTheDocument();
  });

  it('renders a referral row with its status and reward', async () => {
    vi.mocked(referralsApi.mine).mockResolvedValue({
      referrals: [entry({ status: 'REWARDED', reward: 250 })],
      walletBalance: 250,
    });
    renderPage();

    expect(await screen.findByText('Jane Doe')).toBeInTheDocument();
    expect(screen.getByText('Rewarded')).toBeInTheDocument();
    expect(screen.getByText(/Earned ₹250/)).toBeInTheDocument();
    expect(screen.getByText('₹250')).toBeInTheDocument();
    expect(screen.queryByText(/no referrals yet/i)).not.toBeInTheDocument();
  });

  it('labels a merely-registered referral distinctly from a subscribed one', async () => {
    vi.mocked(referralsApi.mine).mockResolvedValue({
      referrals: [entry({ status: 'SUBSCRIBED' })],
      walletBalance: 0,
    });
    renderPage();

    expect(await screen.findByText('Subscribed')).toBeInTheDocument();
  });
});
