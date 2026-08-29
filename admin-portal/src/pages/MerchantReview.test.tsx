import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import MerchantReview from './MerchantReview';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminMerchantReviewApi } from '../api/endpoints';
import type { MerchantReviewItem } from '../types';

// AdminLayout now renders ThemeToggle (dark-mode support), which calls useTheme() --
// same reason adminSearchApi is stubbed below for GlobalSearch: a real ThemeProvider isn't
// mounted in these tests, so without this mock every AdminLayout-wrapped page throws before
// any assertion runs.
vi.mock('../context/ThemeContext', () => ({
  useTheme: () => ({ theme: 'system', resolvedTheme: 'light', setTheme: vi.fn() }),
}));
vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
  adminMerchantReviewApi: {
    queue: vi.fn(),
    count: vi.fn(),
    mergeCandidates: vi.fn(),
    approve: vi.fn(),
    approveAll: vi.fn(),
    rename: vi.fn(),
    merge: vi.fn(),
    discard: vi.fn(),
  },
}));

const guessWithNoHistory: MerchantReviewItem = {
  id: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
  userId: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
  userEmail: 'owner@example.com',
  canonicalName: 'SWIGGYORDR9182',
  lifecycleStatus: 'TEMPORARY',
  transactionCount: 0,
  createdAt: '2026-08-07T09:00:00Z',
};

const guessOnTheLedger: MerchantReviewItem = {
  ...guessWithNoHistory,
  id: 'cccccccc-cccc-cccc-cccc-cccccccccccc',
  canonicalName: 'AMZNMKTPLACE',
  transactionCount: 12,
};

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <MerchantReview />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function mockAuth(permissions: string[]) {
  vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
    hasPermission: (p: string) => permissions.includes(p),
    permissions,
    fullName: 'Ops Admin',
  }));
}

function queueOf(...rows: MerchantReviewItem[]) {
  return { content: rows, page: 0, size: 25, totalElements: rows.length, totalPages: 1 };
}

describe('MerchantReview', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(adminMerchantReviewApi.mergeCandidates).mockResolvedValue([]);
  });

  it('is gated on MERCHANT_REVIEW, not on MERCHANT_MANAGE', () => {
    mockAuth(['MERCHANT_MANAGE']);
    vi.mocked(adminMerchantReviewApi.queue).mockResolvedValue(queueOf());
    renderPage();

    expect(screen.getByText(/don't have access to this section/i)).toBeInTheDocument();
  });

  it('shows the engine guess with the owning account and its ledger footprint', async () => {
    mockAuth(['MERCHANT_REVIEW']);
    vi.mocked(adminMerchantReviewApi.queue).mockResolvedValue(queueOf(guessOnTheLedger));
    renderPage();

    await waitFor(() => expect(screen.getByText('AMZNMKTPLACE')).toBeInTheDocument());
    expect(screen.getByText('owner@example.com')).toBeInTheDocument();
    expect(screen.getByText('12 transactions')).toBeInTheDocument();
  });

  /**
   * The safety property, expressed in the UI.
   *
   * transactions.merchant_id is ON DELETE SET NULL, so discarding a merchant with history would
   * silently strip the attribution from real ledger rows. The backend refuses it with a 409; the
   * page does not offer a button whose only possible outcome is that refusal, and says why.
   */
  it('offers no discard for a merchant that is on the ledger, and explains why', async () => {
    mockAuth(['MERCHANT_REVIEW']);
    vi.mocked(adminMerchantReviewApi.queue).mockResolvedValue(queueOf(guessOnTheLedger));
    renderPage();

    await waitFor(() => expect(screen.getByText('AMZNMKTPLACE')).toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: 'Review' }));

    expect(screen.queryByRole('button', { name: /Discard/ })).not.toBeInTheDocument();
    expect(screen.getByText(/Cannot discard/)).toBeInTheDocument();
    expect(screen.getByText(/Merge it\s+instead/)).toBeInTheDocument();
  });

  it('offers discard for a guess nothing points at', async () => {
    mockAuth(['MERCHANT_REVIEW']);
    vi.mocked(adminMerchantReviewApi.queue).mockResolvedValue(queueOf(guessWithNoHistory));
    renderPage();

    await waitFor(() => expect(screen.getByText('SWIGGYORDR9182')).toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: 'Review' }));

    expect(screen.getByRole('button', { name: /Discard/ })).toBeInTheDocument();
  });

  it('approves a guess against its owning user', async () => {
    mockAuth(['MERCHANT_REVIEW']);
    vi.mocked(adminMerchantReviewApi.queue).mockResolvedValue(queueOf(guessWithNoHistory));
    vi.mocked(adminMerchantReviewApi.approve).mockResolvedValue({
      ...guessWithNoHistory, lifecycleStatus: 'APPROVED',
    });
    renderPage();

    await waitFor(() => expect(screen.getByText('SWIGGYORDR9182')).toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: 'Approve' }));

    // Scoped to the owner, which is the product decision: a merchant belongs to exactly one user.
    await waitFor(() => expect(adminMerchantReviewApi.approve)
      .toHaveBeenCalledWith(guessWithNoHistory.userId, guessWithNoHistory.id));
  });

  it('renames a guess and approves it in one action', async () => {
    mockAuth(['MERCHANT_REVIEW']);
    vi.mocked(adminMerchantReviewApi.queue).mockResolvedValue(queueOf(guessWithNoHistory));
    vi.mocked(adminMerchantReviewApi.rename).mockResolvedValue({
      ...guessWithNoHistory, canonicalName: 'Swiggy', lifecycleStatus: 'APPROVED',
    });
    renderPage();

    await waitFor(() => expect(screen.getByText('SWIGGYORDR9182')).toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: 'Review' }));

    const input = screen.getByLabelText(/Correct the name/i);
    await userEvent.clear(input);
    await userEvent.type(input, 'Swiggy');
    await userEvent.click(screen.getByRole('button', { name: /Rename & approve/i }));

    await waitFor(() => expect(adminMerchantReviewApi.rename)
      .toHaveBeenCalledWith(guessWithNoHistory.userId, guessWithNoHistory.id, 'Swiggy'));
  });

  /** Merge candidates come from the owner's own merchants. There is no canonical registry, so a
   *  cross-user merge is not something the page can offer. */
  it('draws merge candidates from the owning account only', async () => {
    mockAuth(['MERCHANT_REVIEW']);
    vi.mocked(adminMerchantReviewApi.queue).mockResolvedValue(queueOf(guessOnTheLedger));
    vi.mocked(adminMerchantReviewApi.mergeCandidates).mockResolvedValue([
      { ...guessWithNoHistory, id: 'dddddddd-dddd-dddd-dddd-dddddddddddd', canonicalName: 'Amazon' },
    ]);
    renderPage();

    await waitFor(() => expect(screen.getByText('AMZNMKTPLACE')).toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: 'Review' }));

    await waitFor(() => expect(adminMerchantReviewApi.mergeCandidates)
      .toHaveBeenCalledWith(guessOnTheLedger.userId, guessOnTheLedger.id));
    await userEvent.click(await screen.findByRole('button', { name: /Amazon/ }));

    await waitFor(() => expect(adminMerchantReviewApi.merge).toHaveBeenCalledWith(
      guessOnTheLedger.userId, guessOnTheLedger.id, 'dddddddd-dddd-dddd-dddd-dddddddddddd'));
  });

  /** The shared Pagination component this page now uses (swapped in for a hand-rolled prev/next
   *  pair) drives its "next page" request off the SAME `page` state the query itself reads --
   *  proving the wiring survived the swap, not just that Pagination renders. */
  it('requests the next page of the queue when Pagination is clicked', async () => {
    mockAuth(['MERCHANT_REVIEW']);
    vi.mocked(adminMerchantReviewApi.queue).mockResolvedValue(
      { content: [guessOnTheLedger], page: 0, size: 25, totalElements: 30, totalPages: 2 }
    );
    renderPage();

    await waitFor(() => expect(screen.getByText('AMZNMKTPLACE')).toBeInTheDocument());
    expect(screen.getByText('Page 1 of 2')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Next page' }));

    await waitFor(() => expect(adminMerchantReviewApi.queue).toHaveBeenCalledWith({ page: 1, size: 25 }));
  });
});
