import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Investments from './Investments';
import { accountsApi, networthApi, entitlementsApi, type NetWorthData, type EntitlementsDto } from '../api/endpoints';
import type { Account } from '../types';

// The charts themselves are not under test here and chart.js needs a real canvas, which jsdom
// doesn't provide -- the page's loading behaviour is what these tests are about.
vi.mock('react-chartjs-2', () => ({
  Doughnut: () => <div data-testid="allocation-chart" />,
  Line: () => <div data-testid="trend-chart" />,
}));

vi.mock('../api/endpoints', () => ({
  accountsApi: { list: vi.fn(), create: vi.fn(), remove: vi.fn() },
  networthApi: { current: vi.fn(), saveSnapshot: vi.fn() },
  entitlementsApi: { mine: vi.fn() },
}));

function entitlements(overrides: Partial<EntitlementsDto> = {}): EntitlementsDto {
  return { planCode: 'PREMIUM', planName: 'Premium', features: { INVESTMENT_INSIGHTS: true }, ...overrides };
}

function renderInvestments() {
  // PremiumFeatureGate (the Add Investment form's INVESTMENT_INSIGHTS gate) needs react-query's
  // context, and its "Upgrade" button navigates via react-router -- neither existed on this page
  // before, so rendering it at all now needs both providers.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <Investments />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function holding(overrides: Partial<Account> = {}): Account {
  return {
    id: 'a1',
    name: 'Index Fund',
    accountType: 'INVESTMENT',
    balance: 50000,
    investmentKind: 'Mutual Fund',
    ...overrides,
  } as Account;
}

function netWorth(overrides: Partial<NetWorthData> = {}): NetWorthData {
  return {
    totalAssets: 50000,
    totalLiabilities: 0,
    netWorth: 50000,
    history: [
      { date: '2026-07-01', netWorth: 40000 },
      { date: '2026-08-01', netWorth: 50000 },
    ],
    ...overrides,
  } as NetWorthData;
}

function pending<T>(): Promise<T> {
  return new Promise<T>(() => {});
}

describe('Investments — loading states', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Entitled by default -- every existing test here predates INVESTMENT_INSIGHTS and expects
    // the real Add Investment form, not the upgrade prompt. The denial tests below override this.
    vi.mocked(entitlementsApi.mine).mockResolvedValue(entitlements());
  });

  /**
   * The page-level `if (loading) return <p>Loading…</p>` is gone in favour of per-section regions.
   * Each announces immediately, before useDelayedLoading's window lets any shape render.
   */
  it('announces each loading section immediately instead of gating the whole page', () => {
    vi.mocked(accountsApi.list).mockReturnValue(pending<Account[]>());
    vi.mocked(networthApi.current).mockReturnValue(pending<NetWorthData>());

    renderInvestments();

    expect(screen.getByText('Loading your investment totals')).toBeInTheDocument();
    expect(screen.getByText('Loading your holdings')).toBeInTheDocument();
    // Both charts route their own loading through ChartContainer's Region.
    expect(screen.getByText('Loading your allocation')).toBeInTheDocument();
    expect(screen.getByText('Loading your net worth trend')).toBeInTheDocument();
  });

  /**
   * Removing the page gate exposes every `length === 0` empty state to the loading window. A user
   * with holdings must never be told they have none just because the fetch hasn't landed --
   * the flash-of-empty-state bug class §2 was written to kill.
   */
  it('never shows an empty state while the initial fetch is still in flight', () => {
    vi.mocked(accountsApi.list).mockReturnValue(pending<Account[]>());
    vi.mocked(networthApi.current).mockReturnValue(pending<NetWorthData>());

    renderInvestments();

    expect(screen.queryByText('No holdings yet')).not.toBeInTheDocument();
    expect(screen.queryByText('No investments yet')).not.toBeInTheDocument();
    expect(screen.queryByText('Building your net worth trend')).not.toBeInTheDocument();
  });

  /**
   * The trap the roadmap missed: load() is also called after add and delete, so a mutation refetch
   * used to re-enter the SAME page-level gate. Turned into a skeleton that would mean the entire
   * page collapsing after every add -- the opposite of the roadmap's UX rule, which puts a refetch
   * on the "stale content stays, spinner shows" row.
   */
  it('does not collapse into a skeleton when a delete triggers a refetch', async () => {
    const user = userEvent.setup();
    vi.mocked(accountsApi.list).mockResolvedValue([holding()]);
    vi.mocked(networthApi.current).mockResolvedValue(netWorth());
    vi.mocked(accountsApi.remove).mockResolvedValue(undefined as never);

    renderInvestments();
    expect(await screen.findByText('Index Fund')).toBeInTheDocument();

    // The refetch after the delete never settles, so the in-flight state is inspectable.
    vi.mocked(accountsApi.list).mockReturnValue(pending<Account[]>());
    vi.mocked(networthApi.current).mockReturnValue(pending<NetWorthData>());

    // Two "Delete" buttons exist once the dialog opens (the row's and the dialog's confirm), so the
    // second click is scoped to the dialog rather than relying on query order.
    await user.click(screen.getByRole('button', { name: 'Delete' }));
    const dialog = await screen.findByRole('alertdialog');
    await user.click(within(dialog).getByRole('button', { name: 'Delete' }));

    expect(await screen.findByText('Refreshing…')).toBeInTheDocument();
    // Content stayed put; no section fell back to a loading region.
    expect(screen.getByText('Index Fund')).toBeInTheDocument();
    expect(screen.queryByText('Loading your holdings')).not.toBeInTheDocument();
    expect(screen.queryByText('Loading your investment totals')).not.toBeInTheDocument();
  });

  /**
   * Keeping the list on screen during a refresh is what makes a SECOND load reachable at all --
   * before Phase 5 the page-level gate replaced every Delete button while the refetch ran. Without
   * a request guard, the first delete's refetch (whose GET was issued BEFORE the second delete)
   * lands last and overwrites the correct result, resurrecting the deleted holding as a live,
   * deletable row until the page is reloaded.
   */
  it('ignores a superseded refetch instead of resurrecting a deleted holding', async () => {
    const user = userEvent.setup();
    const alpha = holding({ id: 'a1', name: 'Alpha' });
    const beta = holding({ id: 'a2', name: 'Beta' });

    const listDeferred: Array<(a: Account[]) => void> = [];
    vi.mocked(accountsApi.list).mockImplementation(
      () => new Promise<Account[]>((resolve) => { listDeferred.push(resolve); })
    );
    vi.mocked(networthApi.current).mockResolvedValue(netWorth());
    vi.mocked(accountsApi.remove).mockResolvedValue(undefined as never);

    renderInvestments();

    // Initial load.
    await waitFor(() => expect(listDeferred).toHaveLength(1));
    listDeferred[0]([alpha, beta]);
    expect(await screen.findByText('Alpha')).toBeInTheDocument();

    async function deleteRow(name: string) {
      const row = screen.getByText(name).closest('div')!;
      await user.click(within(row).getByRole('button', { name: 'Delete' }));
      const dialog = await screen.findByRole('alertdialog');
      await user.click(within(dialog).getByRole('button', { name: 'Delete' }));
    }

    await deleteRow('Alpha');
    await waitFor(() => expect(listDeferred).toHaveLength(2));
    // Second delete starts while the first refetch is still in flight.
    await deleteRow('Beta');
    await waitFor(() => expect(listDeferred).toHaveLength(3));

    // The newer refetch (post-both-deletes) resolves first...
    listDeferred[2]([]);
    await waitFor(() => expect(screen.queryByText('Beta')).not.toBeInTheDocument());

    // ...then the superseded one lands with its pre-second-delete snapshot. It must be ignored.
    listDeferred[1]([beta]);
    await act(async () => { await Promise.resolve(); });

    expect(screen.queryByText('Beta')).not.toBeInTheDocument();
    expect(screen.queryByText('Alpha')).not.toBeInTheDocument();
  });

  /**
   * The empty states are gated on `loading`, which the shared `.finally` clears on the error path
   * too -- so a failed initial fetch used to leave a user with holdings told, in three places, that
   * they have none.
   */
  it('does not claim the user has nothing when the initial fetch failed', async () => {
    vi.mocked(accountsApi.list).mockRejectedValue(new Error('offline'));
    vi.mocked(networthApi.current).mockRejectedValue(new Error('offline'));

    renderInvestments();

    expect(await screen.findByText('Could not load investments.')).toBeInTheDocument();
    expect(screen.queryByText('No holdings yet')).not.toBeInTheDocument();
    expect(screen.queryByText('No investments yet')).not.toBeInTheDocument();
    expect(screen.queryByText('Building your net worth trend')).not.toBeInTheDocument();
  });

  it('keeps the Add button spinning until the refetched list is on screen', async () => {
    const user = userEvent.setup();
    vi.mocked(accountsApi.list).mockResolvedValue([]);
    vi.mocked(networthApi.current).mockResolvedValue(netWorth({ history: [] }));
    vi.mocked(accountsApi.create).mockResolvedValue(holding() as never);

    renderInvestments();
    await screen.findByText('No holdings yet');

    await user.type(screen.getByLabelText('Name'), 'Index Fund');
    await user.type(screen.getByLabelText('Current value'), '50000');

    // create() resolves but the follow-up refetch does not -- the button must still be busy.
    vi.mocked(accountsApi.list).mockReturnValue(pending<Account[]>());
    vi.mocked(networthApi.current).mockReturnValue(pending<NetWorthData>());

    await user.click(screen.getByRole('button', { name: 'Add' }));

    // Its accessible name gains the busy suffix while loading, which is itself the assertion that
    // the pending state is announced rather than conveyed only as "disabled".
    await waitFor(() => expect(screen.getByRole('button', { name: /^Add\s*,\s*loading$/ })).toBeDisabled());
  });

  // INVESTMENT_INSIGHTS: adding a new holding is Premium-only; an existing holding (from before a
  // downgrade, or added by an admin on the user's behalf) still shows and can still be deleted.
  describe('INVESTMENT_INSIGHTS gating', () => {
    it('shows an upgrade prompt instead of the Add Investment form for a non-entitled user', async () => {
      vi.mocked(entitlementsApi.mine).mockResolvedValue(entitlements({ planCode: 'FREE', features: {} }));
      vi.mocked(accountsApi.list).mockResolvedValue([]);
      vi.mocked(networthApi.current).mockResolvedValue(netWorth({ history: [] }));

      renderInvestments();

      expect(await screen.findByRole('button', { name: /upgrade/i })).toBeInTheDocument();
      expect(screen.queryByLabelText('Name')).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /^Add$/ })).not.toBeInTheDocument();
    });

    it('still shows an existing holding, with a working Delete, for a user who added it before a downgrade', async () => {
      // The Upgrade prompt (replacing the Add-new form) and the existing holdings list are
      // independent sections of the same card -- a non-entitled user with a holding from before
      // a downgrade sees BOTH: no way to add another, but full access to what they already have.
      const user = userEvent.setup();
      vi.mocked(entitlementsApi.mine).mockResolvedValue(entitlements({ planCode: 'FREE', features: {} }));
      vi.mocked(accountsApi.list).mockResolvedValue([holding()]);
      vi.mocked(networthApi.current).mockResolvedValue(netWorth());
      vi.mocked(accountsApi.remove).mockResolvedValue(undefined as never);

      renderInvestments();
      expect(await screen.findByText('Index Fund')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /upgrade/i })).toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: 'Delete' }));
      const dialog = await screen.findByRole('alertdialog');
      await user.click(within(dialog).getByRole('button', { name: 'Delete' }));

      await waitFor(() => expect(accountsApi.remove).toHaveBeenCalledWith('a1'));
    });
  });
});
