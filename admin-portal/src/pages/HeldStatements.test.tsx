import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import HeldStatements from './HeldStatements';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminHeldStatementApi } from '../api/endpoints';
import type { HeldStatementRow } from '../types';

// Same reason HeldImports.test.tsx mocks these two: AdminLayout renders ThemeToggle (calls
// useTheme()) and there's no real ThemeProvider mounted here.
vi.mock('../context/ThemeContext', () => ({
  useTheme: () => ({ theme: 'system', resolvedTheme: 'light', setTheme: vi.fn() }),
}));
vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
  adminHeldStatementApi: {
    list: vi.fn(),
  },
}));

const olderRow: HeldStatementRow = {
  id: '11111111-1111-1111-1111-111111111111',
  heldId: 'HLD-2026-100001',
  importJobId: '33333333-3333-3333-3333-333333333333',
  userId: '22222222-2222-2222-2222-222222222222',
  bankName: 'HDFC Bank',
  status: 'HELD',
  triggerSummary: 'Printed and parsed transaction count disagree (ROW_GROUPING)',
  reliabilityStatus: 'NEEDS_ATTENTION',
  textSource: 'NATIVE',
  headerReconstructionUncertain: false,
  parserVersion: 'abc123',
  assignedEngineerId: null,
  engineerNotes: null,
  rootCause: null,
  fixReference: null,
  createdAt: '2026-09-01T08:00:00Z',
  assignedAt: null,
  readyAt: null,
  resolvedAt: null,
};

const newerRow: HeldStatementRow = {
  ...olderRow,
  id: '44444444-4444-4444-4444-444444444444',
  heldId: 'HLD-2026-100002',
  userId: '55555555-5555-5555-5555-555555555555',
  bankName: 'ICICI Bank',
  createdAt: '2026-09-02T08:00:00Z',
};

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <HeldStatements />
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

describe('HeldStatements', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(adminHeldStatementApi.list).mockResolvedValue({
      content: [olderRow, newerRow], page: 0, size: 25, totalElements: 2, totalPages: 1,
    });
  });

  it('is gated on TRUST_REVIEW_MANAGE, not on any permission an admin happens to hold', () => {
    mockAuth(['IMPORT_TRIAGE_MANAGE', 'PLATFORM_DIAGNOSTICS_VIEW']);
    renderPage();

    expect(screen.getByText(/don't have access to this section/i)).toBeInTheDocument();
    expect(adminHeldStatementApi.list).not.toHaveBeenCalled();
  });

  /** The reference an operator quotes -- never the raw UUID underneath it. */
  it('shows the Held ID, not a raw UUID', async () => {
    mockAuth(['TRUST_REVIEW_MANAGE']);
    renderPage();

    expect(await screen.findByText('HLD-2026-100001')).toBeInTheDocument();
    expect(screen.queryByText(olderRow.id)).not.toBeInTheDocument();
  });

  /** Ordering is the server's answer (oldest first, the longest-waiting user is the one to look
   *  at) -- this only has to prove the table renders what it was given, in the order it was
   *  given, rather than silently re-sorting. */
  it('shows the oldest hold first', async () => {
    mockAuth(['TRUST_REVIEW_MANAGE']);
    renderPage();

    const heldIds = (await screen.findAllByText(/^HLD-2026-\d+$/)).map((el) => el.textContent);
    expect(heldIds).toEqual(['HLD-2026-100001', 'HLD-2026-100002']);
  });

  it('renders an empty state rather than a blank table when nothing is held', async () => {
    vi.mocked(adminHeldStatementApi.list).mockResolvedValue({
      content: [], page: 0, size: 25, totalElements: 0, totalPages: 0,
    });
    mockAuth(['TRUST_REVIEW_MANAGE']);
    renderPage();

    expect(await screen.findByText(/nothing is held for trust review/i)).toBeInTheDocument();
  });

  /**
   * The queue is a worklist, not a statement viewer. `HeldStatementRow` carries no statement
   * content or object key at all (see the type's own doc), so this is really asserting the page
   * doesn't invent a way to show one -- there's nothing in the fixture data for it to leak.
   */
  it('never renders statement content in the list', async () => {
    mockAuth(['TRUST_REVIEW_MANAGE']);
    renderPage();
    await screen.findByText('HLD-2026-100001');

    expect(screen.queryByText(/statementObjectKey/i)).not.toBeInTheDocument();
  });

  it('shows the snapshotted bank name and the bare user id', async () => {
    mockAuth(['TRUST_REVIEW_MANAGE']);
    renderPage();

    expect(await screen.findByText('HDFC Bank')).toBeInTheDocument();
    expect(screen.getByText(olderRow.userId)).toBeInTheDocument();
  });

  it('applies the status filter and refetches', async () => {
    mockAuth(['TRUST_REVIEW_MANAGE']);
    renderPage();
    await screen.findByText('HLD-2026-100001');

    await userEvent.selectOptions(screen.getByLabelText(/filter by status/i), 'ASSIGNED');

    expect(adminHeldStatementApi.list).toHaveBeenLastCalledWith(
      expect.objectContaining({ status: 'ASSIGNED', page: 0 }));
  });

  it('applies the bank and engineer filters together on Filter', async () => {
    mockAuth(['TRUST_REVIEW_MANAGE']);
    renderPage();
    await screen.findByText('HLD-2026-100001');

    await userEvent.type(screen.getByPlaceholderText(/bank name/i), 'HDFC Bank');
    await userEvent.type(screen.getByPlaceholderText(/assigned engineer id/i), 'eng-1');
    await userEvent.click(screen.getByRole('button', { name: /^filter$/i }));

    expect(adminHeldStatementApi.list).toHaveBeenLastCalledWith(
      expect.objectContaining({ bank: 'HDFC Bank', engineerId: 'eng-1', page: 0 }));
  });

  it('applies the age filter as hours', async () => {
    mockAuth(['TRUST_REVIEW_MANAGE']);
    renderPage();
    await screen.findByText('HLD-2026-100001');

    await userEvent.type(screen.getByPlaceholderText(/hours/i), '48');

    expect(adminHeldStatementApi.list).toHaveBeenLastCalledWith(
      expect.objectContaining({ olderThanHours: 48, page: 0 }));
  });
});
