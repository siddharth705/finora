import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { TopBar } from './TopBar';
import { AuthProvider } from '../context/AuthContext';
import { ThemeProvider } from '../context/ThemeContext';
import { dashboardApi, accountsApi, categoriesApi, transactionsApi } from '../api/endpoints';

// Covers only the global Add Transaction button this session added -- search, notifications,
// theme and the profile menu predate this and aren't the subject of this change.
vi.mock('../api/endpoints', () => ({
  dashboardApi: { summary: vi.fn() },
  accountsApi: { list: vi.fn() },
  categoriesApi: { list: vi.fn() },
  transactionsApi: { create: vi.fn() },
}));

function renderTopBar() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ThemeProvider>
          <AuthProvider>
            <TopBar />
          </AuthProvider>
        </ThemeProvider>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('TopBar — Add Transaction', () => {
  const ACCOUNT = {
    id: 'acct-1', name: 'HDFC Savings', accountType: 'SAVINGS' as const, balance: 0,
    bank: {
      id: 'hdfc', officialName: 'HDFC Bank', shortName: 'HDFC', colorHex: '#004c8f', initials: 'HD',
      logoPath: '/banks/hdfc.svg', category: 'PRIVATE' as const, websiteUrl: 'https://hdfcbank.com',
      ifscPrefix: 'HDFC', supportedAccountTypes: ['SAVINGS'],
    },
  } as any;

  beforeEach(() => {
    vi.mocked(dashboardApi.summary).mockReset().mockResolvedValue({ notifications: [] } as any);
    vi.mocked(accountsApi.list).mockReset().mockResolvedValue([ACCOUNT]);
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([{ id: 'cat-1', name: 'Groceries' } as any]);
    vi.mocked(transactionsApi.create).mockReset();
  });

  it('opens the shared Add Transaction modal, reachable from any /app/* page', async () => {
    const user = userEvent.setup();
    renderTopBar();

    await user.click(screen.getByRole('button', { name: /add transaction/i }));

    expect(await screen.findByRole('heading', { name: /add transaction/i })).toBeInTheDocument();
    expect(await screen.findByText('HDFC Savings')).toBeInTheDocument();
  });

  it('creates a transaction and closes the modal on success', async () => {
    vi.mocked(transactionsApi.create).mockResolvedValue({} as any);
    const user = userEvent.setup();
    renderTopBar();
    await user.click(screen.getByRole('button', { name: /add transaction/i }));
    // Scoped to the modal from here on -- TopBar's own button that opened it is still mounted
    // underneath and shares the same accessible name.
    const modal = within((await screen.findByRole('heading', { name: /add transaction/i })).closest('.bg-card') as HTMLElement);

    await user.type(modal.getByLabelText(/description/i), 'Coffee');
    await user.type(modal.getByLabelText(/amount/i), '150');
    await user.click(modal.getByRole('button', { name: /^add transaction$/i }));

    await waitFor(() => expect(transactionsApi.create).toHaveBeenCalledWith(
      expect.objectContaining({ accountId: 'acct-1', description: 'Coffee', amount: 150 })
    ));
    await waitFor(() => expect(screen.queryByRole('heading', { name: /add transaction/i })).not.toBeInTheDocument());
  });
});
