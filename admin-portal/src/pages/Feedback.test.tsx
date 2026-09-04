import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Feedback from './Feedback';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminFeedbackApi } from '../api/endpoints';
import type { FeedbackBreakdown, FeedbackRow } from '../types';

vi.mock('../context/ThemeContext', () => ({
  useTheme: () => ({ theme: 'system', resolvedTheme: 'light', setTheme: vi.fn() }),
}));
vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
  adminFeedbackApi: { list: vi.fn(), breakdown: vi.fn() },
}));

const row: FeedbackRow = {
  id: 'fb-1',
  userId: '22222222-2222-2222-2222-222222222222',
  type: 'BUG',
  context: 'IMPORT_FLOW',
  source: 'WEB',
  message: 'Import silently drops rows when the file has a BOM.',
  createdAt: '2026-09-01T08:00:00Z',
};

const breakdown: FeedbackBreakdown = {
  total: 10,
  byType: [{ label: 'BUG', total: 7 }, { label: 'FEATURE_REQUEST', total: 3 }],
  byContext: [{ label: 'IMPORT_FLOW', total: 8 }, { label: 'HELP', total: 2 }],
  bySource: [{ label: 'WEB', total: 7 }, { label: 'MOBILE_ANDROID', total: 3 }],
};

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <Feedback />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function mockAuth(permissions: string[]) {
  vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
    hasPermission: (p: string) => permissions.includes(p),
    permissions,
  }));
}

describe('Feedback', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockAuth(['SUPPORT_MANAGE']);
    vi.mocked(adminFeedbackApi.list).mockResolvedValue({
      content: [row], page: 0, size: 25, totalElements: 1, totalPages: 1,
    });
    vi.mocked(adminFeedbackApi.breakdown).mockResolvedValue(breakdown);
  });

  it('is gated on SUPPORT_MANAGE', () => {
    mockAuth(['TRUST_REVIEW_MANAGE']);
    renderPage();

    expect(screen.getByText(/don't have access to this section/i)).toBeInTheDocument();
    expect(adminFeedbackApi.list).not.toHaveBeenCalled();
  });

  it('renders the breakdown panels by type, context and source with the grand total', async () => {
    renderPage();

    expect(await screen.findByText('By Type (10 total)')).toBeInTheDocument();
    expect(screen.getByText('BUG')).toBeInTheDocument();
    // Labels display with underscores replaced by spaces, same convention as the status/category
    // labels elsewhere in the admin portal (e.g. HeldStatements.tsx's own status cell).
    expect(screen.getByText('IMPORT FLOW')).toBeInTheDocument();
    expect(screen.getByText('MOBILE ANDROID')).toBeInTheDocument();
  });

  it('lists a feedback row with its type, context, source and message', async () => {
    renderPage();
    // findByRole('table') resolves the moment the (empty, "Loading…") table mounts, not once its
    // query has resolved -- waiting on the row's own text first is what actually waits for data.
    await screen.findByText(/Import silently drops rows/);

    const table = within(screen.getByRole('table'));
    expect(table.getByText('Bug')).toBeInTheDocument();
    expect(table.getByText('Import flow')).toBeInTheDocument();
    expect(table.getByText('WEB')).toBeInTheDocument();
    expect(table.getByText(/Import silently drops rows/)).toBeInTheDocument();
  });

  it('breakdown is fetched once and never refetched when the list filter changes', async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('By Type (10 total)');
    expect(adminFeedbackApi.breakdown).toHaveBeenCalledTimes(1);

    await user.selectOptions(screen.getByLabelText(/filter by type/i), 'BUG');

    expect(adminFeedbackApi.list).toHaveBeenLastCalledWith(expect.objectContaining({ type: 'BUG' }));
    expect(adminFeedbackApi.breakdown).toHaveBeenCalledTimes(1);
  });

  it('shows "No feedback yet" for an empty breakdown dimension rather than an empty bar list', async () => {
    vi.mocked(adminFeedbackApi.breakdown).mockResolvedValue({ total: 0, byType: [], byContext: [], bySource: [] });
    renderPage();

    expect((await screen.findAllByText('No feedback yet.')).length).toBe(3);
  });
});
