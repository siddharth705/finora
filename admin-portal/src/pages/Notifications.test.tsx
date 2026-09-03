import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Notifications from './Notifications';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminNotificationApi } from '../api/endpoints';
import type { NotificationAdminRow, NotificationAdminDetail } from '../types';

// Same reason LearningQueue.test.tsx mocks these two: AdminLayout renders ThemeToggle (calls
// useTheme()) and there's no real ThemeProvider mounted here, so every AdminLayout-wrapped page
// throws before any assertion runs without this.
vi.mock('../context/ThemeContext', () => ({
  useTheme: () => ({ theme: 'system', resolvedTheme: 'light', setTheme: vi.fn() }),
}));
vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
  adminNotificationApi: {
    list: vi.fn(),
    summary: vi.fn(),
    get: vi.fn(),
  },
}));

const failedNotification: NotificationAdminRow = {
  id: '11111111-1111-1111-1111-111111111111',
  userId: '22222222-2222-2222-2222-222222222222',
  type: 'IMPORT_STATEMENT_READY',
  category: 'FINANCIAL',
  channel: 'EMAIL',
  priority: 'NORMAL',
  status: 'DEAD_LETTER',
  title: 'Your HDFC statement is ready',
  attemptCount: 5,
  nextAttemptAt: null,
  lastError: 'ResendApiException: 502 Bad Gateway',
  sentAt: null,
  createdAt: '2026-08-07T08:59:00Z',
};

const detail: NotificationAdminDetail = {
  ...failedNotification,
  message: 'We finished processing your HDFC statement and imported it successfully.',
  attempts: [
    {
      id: 'a2', provider: 'resend', response: 'ok', success: false, attempt: 2,
      timestamp: '2026-08-07T10:05:00Z',
    },
    {
      id: 'a1', provider: 'resend', response: 'connection reset', success: false, attempt: 1,
      timestamp: '2026-08-07T09:00:00Z',
    },
  ],
};

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <Notifications />
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

describe('Notifications', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(adminNotificationApi.summary).mockResolvedValue({
      sent: 12, failed: 1,
      byChannel: [
        { channel: 'EMAIL', sent: 10, failed: 1 },
        { channel: 'SMS', sent: 0, failed: 0 },
        { channel: 'PUSH', sent: 2, failed: 0 },
      ],
    });
    vi.mocked(adminNotificationApi.list).mockResolvedValue({
      content: [failedNotification], page: 0, size: 25, totalElements: 1, totalPages: 1,
    });
    vi.mocked(adminNotificationApi.get).mockResolvedValue(detail);
  });

  it('is gated on NOTIFICATION_MANAGE, not on any permission an admin happens to hold', () => {
    mockAuth(['MERCHANT_MANAGE', 'PLATFORM_DIAGNOSTICS_VIEW']);
    renderPage();

    expect(screen.getByText(/don't have access to this section/i)).toBeInTheDocument();
  });

  it('shows send-outcome counts and the failed notification, with the recipient as an id not an email', async () => {
    mockAuth(['NOTIFICATION_MANAGE']);
    renderPage();

    await waitFor(() => expect(screen.getByText('Your HDFC statement is ready')).toBeInTheDocument());
    // Stat tiles.
    expect(screen.getByText('12')).toBeInTheDocument();
    expect(screen.getByText('1')).toBeInTheDocument();
    // The row shows a bare id, never an email or phone number -- this dashboard has neither
    // available to it (AdminNotificationController never joins to user contact details). Scoped
    // to the id element's own row, not the whole document, since AdminLayout's own header shows
    // the logged-in admin's email elsewhere on the page.
    const idCell = screen.getByText(failedNotification.userId);
    expect(idCell).toBeInTheDocument();
    expect(idCell.closest('tr')).not.toHaveTextContent(/@/);
  });

  /** The acceptance test for the detail view: an operator can see what was sent, why it failed,
   *  and the full provider attempt history, in the server's own newest-first order. */
  it('shows the message and the attempt log newest-first when Details is opened', async () => {
    mockAuth(['NOTIFICATION_MANAGE']);
    renderPage();

    await waitFor(() => expect(screen.getByText('Your HDFC statement is ready')).toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: 'Details' }));

    await waitFor(() => expect(adminNotificationApi.get).toHaveBeenCalledWith(failedNotification.id));
    await waitFor(() => expect(screen.getByText(/finished processing your HDFC statement/)).toBeInTheDocument());

    const attemptEls = await screen.findAllByText(/attempt \d/);
    expect(attemptEls[0]).toHaveTextContent('attempt 2');
    expect(attemptEls[1]).toHaveTextContent('attempt 1');
  });

  it('offers no retry, resend or any other mutating action', async () => {
    mockAuth(['NOTIFICATION_MANAGE']);
    renderPage();

    await waitFor(() => expect(screen.getByText('Your HDFC statement is ready')).toBeInTheDocument());
    // Exact names, not a /retry/i regex -- the RETRYING status filter chip legitimately contains
    // "retry" as a substring and isn't a mutating action.
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Retry now' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Resend' })).not.toBeInTheDocument();
  });

  it('requests the next page of the outbox when Pagination is clicked', async () => {
    mockAuth(['NOTIFICATION_MANAGE']);
    vi.mocked(adminNotificationApi.list).mockResolvedValue({
      content: [failedNotification], page: 0, size: 25, totalElements: 30, totalPages: 2,
    });
    renderPage();

    await waitFor(() => expect(screen.getByText('Your HDFC statement is ready')).toBeInTheDocument());
    expect(screen.getByText('Page 1 of 2')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Next page' }));

    await waitFor(() => expect(adminNotificationApi.list)
      .toHaveBeenCalledWith({ status: 'DEAD_LETTER', page: 1, size: 25 }));
  });

  it('switches the status filter and re-queries the list', async () => {
    mockAuth(['NOTIFICATION_MANAGE']);
    renderPage();

    await waitFor(() => expect(screen.getByText('Your HDFC statement is ready')).toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: 'SENT' }));

    await waitFor(() => expect(adminNotificationApi.list)
      .toHaveBeenCalledWith({ status: 'SENT', page: 0, size: 25 }));
  });
});
