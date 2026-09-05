import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Referrals from './Referrals';
import { referralsApi } from '../api/endpoints';

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

describe('Referrals', () => {
  beforeEach(() => {
    vi.mocked(referralsApi.mine).mockReset();
  });

  it('shows a zero count when nothing has happened yet', async () => {
    vi.mocked(referralsApi.mine).mockResolvedValue({ code: 'ABCD1234', referralCount: 0 });
    renderPage();

    expect(await screen.findByText('0')).toBeInTheDocument();
  });

  it("renders the user's own referral link once the code loads", async () => {
    vi.mocked(referralsApi.mine).mockResolvedValue({ code: 'ABCD1234', referralCount: 0 });
    renderPage();

    const input = await screen.findByDisplayValue(/\/register\?ref=ABCD1234$/);
    expect(input).toBeInTheDocument();
  });

  it('renders the real referral count once it loads', async () => {
    vi.mocked(referralsApi.mine).mockResolvedValue({ code: 'ABCD1234', referralCount: 7 });
    renderPage();

    expect(await screen.findByText('7')).toBeInTheDocument();
  });
});
