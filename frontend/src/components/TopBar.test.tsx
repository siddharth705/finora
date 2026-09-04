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
  // Support, Help & Feedback v1, Phase 8: FeedbackModal (rendered from the Help menu below) calls
  // this directly, so it needs a mock here too or mounting the modal throws.
  feedbackApi: { submit: vi.fn() },
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

  /**
   * `POST /transactions` moves the account balance on every call, so a double-click or a retried
   * request whose response was lost does not merely duplicate a row -- it overstates the balance,
   * permanently and silently (ReconciliationService can flag the second row DUPLICATE, but a
   * DUPLICATE row still counts toward Account.balance).
   *
   * The server side of this shipped in V97 and sat inert, because no client ever sent a key.
   */
  describe('idempotency key', () => {
    it('sends one, so V97 protection is actually exercised', async () => {
      vi.mocked(transactionsApi.create).mockResolvedValue({} as any);
      const user = userEvent.setup();
      renderTopBar();
      await user.click(screen.getByRole('button', { name: /add transaction/i }));
      const modal = within((await screen.findByRole('heading', { name: /add transaction/i })).closest('.bg-card') as HTMLElement);
      await user.type(modal.getByLabelText(/description/i), 'Coffee');
      await user.type(modal.getByLabelText(/amount/i), '150');
      await user.click(modal.getByRole('button', { name: /^add transaction$/i }));

      await waitFor(() => expect(transactionsApi.create).toHaveBeenCalledWith(
        expect.objectContaining({ idempotencyKey: expect.any(String) })
      ));
      // The pre-existing assertion above uses objectContaining, so it would have passed happily
      // whether or not a key was sent -- this is the one that actually pins it.
      expect(vi.mocked(transactionsApi.create).mock.calls[0][0].idempotencyKey).toBeTruthy();
    });

    it('reuses the same key when the user retries after a failure', async () => {
      // This is the whole point. A retry must be recognisable as the SAME attempt: if the first
      // request actually reached the server and committed, resending a fresh key would create a
      // second transaction and move the balance again -- the exact bug the key exists to prevent.
      vi.mocked(transactionsApi.create)
        .mockRejectedValueOnce(new Error('network'))
        .mockResolvedValueOnce({} as any);
      const user = userEvent.setup();
      renderTopBar();
      await user.click(screen.getByRole('button', { name: /add transaction/i }));
      const modal = within((await screen.findByRole('heading', { name: /add transaction/i })).closest('.bg-card') as HTMLElement);
      await user.type(modal.getByLabelText(/description/i), 'Coffee');
      await user.type(modal.getByLabelText(/amount/i), '150');

      await user.click(modal.getByRole('button', { name: /^add transaction$/i }));
      await waitFor(() => expect(transactionsApi.create).toHaveBeenCalledTimes(1));
      await user.click(modal.getByRole('button', { name: /^add transaction$/i }));
      await waitFor(() => expect(transactionsApi.create).toHaveBeenCalledTimes(2));

      const calls = vi.mocked(transactionsApi.create).mock.calls;
      // Assert the key EXISTS before asserting the two match: `undefined === undefined` is true, so
      // an equality check alone passes just as happily when no key is sent at all.
      expect(calls[0][0].idempotencyKey).toEqual(expect.any(String));
      expect(calls[1][0].idempotencyKey).toBe(calls[0][0].idempotencyKey);
    });
  });
});

// Support, Help & Feedback v1, Phase 8: "Send feedback" used to be a mailto: link; it's now the
// FeedbackModal, and the Help menu also gained a "My Tickets" entry -- both new, so covered here
// rather than folded into the pre-existing Add Transaction suite above.
describe('TopBar — Help menu', () => {
  beforeEach(() => {
    vi.mocked(dashboardApi.summary).mockReset().mockResolvedValue({ notifications: [] } as any);
  });

  it('links "My Tickets" to the authenticated support route', async () => {
    const user = userEvent.setup();
    renderTopBar();

    await user.click(screen.getByTitle('Help'));
    const link = screen.getByRole('link', { name: /my tickets/i });
    expect(link).toHaveAttribute('href', '/app/support');
  });

  it('opens FeedbackModal from "Send feedback" instead of a mailto link', async () => {
    const user = userEvent.setup();
    renderTopBar();

    await user.click(screen.getByTitle('Help'));
    expect(screen.queryByRole('link', { name: /send feedback/i })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /send feedback/i }));
    expect(screen.getByRole('heading', { name: /send feedback/i })).toBeInTheDocument();
  });
});
