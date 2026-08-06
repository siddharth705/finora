import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import LearningQueue from './LearningQueue';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminLearningQueueApi } from '../api/endpoints';
import type { LearningQueueEvent } from '../types';

vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
  adminLearningQueueApi: {
    list: vi.fn(),
    summary: vi.fn(),
    get: vi.fn(),
    retry: vi.fn(),
    retryAll: vi.fn(),
    resolve: vi.fn(),
  },
}));

const failedEvent: LearningQueueEvent = {
  id: '11111111-1111-1111-1111-111111111111',
  status: 'FAILED',
  attemptCount: 5,
  maxAttempts: 5,
  retryable: true,
  nextAttemptAt: '2026-08-07T10:16:00Z',
  lastError: 'DataIntegrityViolationException: duplicate key value violates unique constraint',
  firstFailedAt: '2026-08-07T09:00:00Z',
  lastRetryAt: '2026-08-07T10:00:00Z',
  createdAt: '2026-08-07T08:59:00Z',
  userId: '22222222-2222-2222-2222-222222222222',
  userEmail: 'affected@example.com',
  merchantId: '33333333-3333-3333-3333-333333333333',
  merchantName: 'SWIGGY',
  categoryId: '44444444-4444-4444-4444-444444444444',
  categoryName: 'Dining',
  statementImportId: '55555555-5555-5555-5555-555555555555',
  statementFileName: 'hdfc-july.pdf',
  importSessionId: null,
};

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <LearningQueue />
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

describe('LearningQueue', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(adminLearningQueueApi.summary).mockResolvedValue({
      pending: 1, processing: 0, failed: 1, completed: 12, resolved: 0,
    });
    vi.mocked(adminLearningQueueApi.list).mockResolvedValue({
      content: [failedEvent], page: 0, size: 25, totalElements: 1, totalPages: 1,
    });
  });

  it('is gated on LEARNING_QUEUE_MANAGE, not on any permission an admin happens to hold', () => {
    mockAuth(['MERCHANT_MANAGE', 'PLATFORM_DIAGNOSTICS_VIEW']);
    renderPage();

    expect(screen.getByText(/don't have access to this section/i)).toBeInTheDocument();
  });

  /**
   * The acceptance test for the whole work item: an operator answers every question from this
   * page, without a database client. Asserting on the NAMES rather than the ids is the point —
   * a page that rendered four UUIDs would pass a weaker test and fail the requirement.
   */
  it('answers what failed, why, for whom and from where without leaving the page', async () => {
    mockAuth(['LEARNING_QUEUE_MANAGE']);
    renderPage();

    // What failed, and what it was trying to learn.
    await waitFor(() => expect(screen.getByText('SWIGGY')).toBeInTheDocument());
    expect(screen.getByText(/Dining/)).toBeInTheDocument();
    // Who was affected -- by email, not just an id.
    expect(screen.getByText('affected@example.com')).toBeInTheDocument();
    // How many retries have happened.
    expect(screen.getByText('5/5')).toBeInTheDocument();

    // Why it failed, and where it came from, are in the detail view.
    await userEvent.click(screen.getByRole('button', { name: 'Details' }));
    expect(screen.getByText(/duplicate key value violates unique constraint/)).toBeInTheDocument();
    expect(screen.getByText('hdfc-july.pdf')).toBeInTheDocument();
  });

  /** An import with no staging session says so. A placeholder id would send an operator chasing a
   *  session that never existed and conclude the data is corrupt. */
  it('says an import had no session rather than inventing one', async () => {
    mockAuth(['LEARNING_QUEUE_MANAGE']);
    renderPage();

    await waitFor(() => expect(screen.getByText('SWIGGY')).toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: 'Details' }));

    expect(screen.getByText(/No session \(direct file import\)/)).toBeInTheDocument();
  });

  it('retries a failed event and refreshes the queue', async () => {
    mockAuth(['LEARNING_QUEUE_MANAGE']);
    vi.mocked(adminLearningQueueApi.retry).mockResolvedValue({
      ...failedEvent, status: 'PENDING', attemptCount: 0, retryable: false, lastError: null,
    });
    renderPage();

    await waitFor(() => expect(screen.getByText('SWIGGY')).toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));

    await waitFor(() => expect(adminLearningQueueApi.retry).toHaveBeenCalledWith(failedEvent.id));
  });

  /**
   * `retryable` is the server's answer, not a re-derivation from `status`. A client that
   * re-implemented the rule would drift from the backend's state machine and offer a button the
   * API refuses -- worse than offering none.
   */
  it('offers no retry for an event the server says is not retryable', async () => {
    mockAuth(['LEARNING_QUEUE_MANAGE']);
    vi.mocked(adminLearningQueueApi.list).mockResolvedValue({
      content: [{ ...failedEvent, status: 'PROCESSING', retryable: false }],
      page: 0, size: 25, totalElements: 1, totalPages: 1,
    });
    renderPage();

    await waitFor(() => expect(screen.getByText('SWIGGY')).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
  });

  it('reports how many events a retry-all actually requeued', async () => {
    mockAuth(['LEARNING_QUEUE_MANAGE']);
    vi.mocked(adminLearningQueueApi.retryAll).mockResolvedValue({ retried: 42 });
    renderPage();

    await waitFor(() => expect(screen.getByText('SWIGGY')).toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: /Retry all failed/i }));

    await waitFor(() => expect(screen.getByText(/42 event\(s\) queued for retry/)).toBeInTheDocument());
  });
});
