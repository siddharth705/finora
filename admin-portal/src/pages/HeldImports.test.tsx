import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import HeldImports from './HeldImports';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminHeldImportApi } from '../api/endpoints';
import type { HeldImportRow, HeldImportDetail } from '../types';

// Same reason Notifications.test.tsx mocks these two: AdminLayout renders ThemeToggle (calls
// useTheme()) and there's no real ThemeProvider mounted here.
vi.mock('../context/ThemeContext', () => ({
  useTheme: () => ({ theme: 'system', resolvedTheme: 'light', setTheme: vi.fn() }),
}));
vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
  adminHeldImportApi: {
    list: vi.fn(),
    summary: vi.fn(),
    get: vi.fn(),
    reprocess: vi.fn(),
    reprocessAll: vi.fn(),
    resolve: vi.fn(),
    download: vi.fn(),
  },
}));

const heldRow: HeldImportRow = {
  id: '11111111-1111-1111-1111-111111111111',
  userId: '22222222-2222-2222-2222-222222222222',
  fileName: 'hdfc-june.pdf',
  sourceFormat: 'PDF',
  failureCode: 'IllegalStateException',
  attemptCount: 2,
  recoveryCount: 0,
  createdAt: '2026-09-01T08:59:00Z',
  heldAt: '2026-09-01T09:02:00Z',
};

const heldDetail: HeldImportDetail = {
  job: heldRow,
  lastError: 'IllegalStateException: no header row found near "Txn Date  Narration"',
  correlationId: 'corr-123',
  objectKey: 'statements/11/22/112233.bin',
};

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <HeldImports />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function mockAuth(permissions: string[], roles: string[] = []) {
  vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
    hasPermission: (p: string) => permissions.includes(p),
    permissions,
    roles,
    fullName: 'Ops Admin',
  }));
}

describe('HeldImports', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(adminHeldImportApi.summary).mockResolvedValue({ held: 3, reprocessing: 1 });
    vi.mocked(adminHeldImportApi.list).mockResolvedValue({
      content: [heldRow], page: 0, size: 25, totalElements: 1, totalPages: 1,
    });
    vi.mocked(adminHeldImportApi.get).mockResolvedValue(heldDetail);
    vi.mocked(adminHeldImportApi.reprocess).mockResolvedValue(heldRow);
    vi.mocked(adminHeldImportApi.reprocessAll).mockResolvedValue({ reprocessed: 3 });
    vi.mocked(adminHeldImportApi.resolve).mockResolvedValue(heldRow);
  });

  it('is gated on IMPORT_TRIAGE_MANAGE, not on any permission an admin happens to hold', () => {
    // Reading a held statement is a different kind of access from watching the pipeline's health.
    mockAuth(['PLATFORM_DIAGNOSTICS_VIEW', 'LEARNING_QUEUE_MANAGE']);
    renderPage();

    expect(screen.getByText(/don't have access to this section/i)).toBeInTheDocument();
    expect(adminHeldImportApi.list).not.toHaveBeenCalled();
  });

  it('lists held statements with the curated failure code', async () => {
    mockAuth(['IMPORT_TRIAGE_MANAGE']);
    renderPage();

    expect(await screen.findByText('hdfc-june.pdf')).toBeInTheDocument();
    expect(screen.getByText('IllegalStateException')).toBeInTheDocument();
  });

  /**
   * The privacy split the whole feature rests on: the list is safe to leave open, the detail view
   * is not. If the raw parser error ever reached the table, twenty-five people's statement content
   * would be on screen without a single audit entry being written.
   */
  it('never shows the raw parser error until a row is opened', async () => {
    mockAuth(['IMPORT_TRIAGE_MANAGE']);
    renderPage();
    await screen.findByText('hdfc-june.pdf');

    expect(screen.queryByText(/no header row found/)).not.toBeInTheDocument();
    expect(adminHeldImportApi.get).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole('button', { name: /details/i }));

    expect(await screen.findByText(/no header row found/)).toBeInTheDocument();
    expect(adminHeldImportApi.get).toHaveBeenCalledWith(heldRow.id);
  });

  it('tells the operator that opening a record is logged against them', async () => {
    mockAuth(['IMPORT_TRIAGE_MANAGE']);
    renderPage();
    await screen.findByText('hdfc-june.pdf');
    await userEvent.click(screen.getByRole('button', { name: /details/i }));

    expect(await screen.findByText(/logged against your account/i)).toBeInTheDocument();
  });

  it('reprocesses one job and refreshes the queue', async () => {
    mockAuth(['IMPORT_TRIAGE_MANAGE']);
    renderPage();
    await screen.findByText('hdfc-june.pdf');
    await userEvent.click(screen.getByRole('button', { name: /details/i }));
    await screen.findByText(/no header row found/);

    await userEvent.click(screen.getByRole('button', { name: /^reprocess$/i }));

    await waitFor(() => expect(adminHeldImportApi.reprocess).toHaveBeenCalledWith(heldRow.id));
  });

  /**
   * A 409 here says something specific and actionable -- "the user already re-uploaded this" --
   * and it is the only part an operator can act on. Swallowing it into a generic toast would throw
   * away the whole message.
   */
  it('surfaces the server\'s own conflict message rather than a generic failure', async () => {
    vi.mocked(adminHeldImportApi.reprocess).mockRejectedValue({
      response: { data: { message: 'This user has already re-uploaded the same statement.' } },
    });
    mockAuth(['IMPORT_TRIAGE_MANAGE']);
    renderPage();
    await screen.findByText('hdfc-june.pdf');
    await userEvent.click(screen.getByRole('button', { name: /details/i }));
    await screen.findByText(/no header row found/);

    await userEvent.click(screen.getByRole('button', { name: /^reprocess$/i }));

    expect(await screen.findByText(/already re-uploaded the same statement/i)).toBeInTheDocument();
  });

  it('passes the operator\'s reason through when giving up on a statement', async () => {
    mockAuth(['IMPORT_TRIAGE_MANAGE']);
    renderPage();
    await screen.findByText('hdfc-june.pdf');
    await userEvent.click(screen.getByRole('button', { name: /details/i }));
    await screen.findByText(/no header row found/);

    await userEvent.type(
      screen.getByLabelText(/give up on this one/i), 'scanned image, no text layer');
    await userEvent.click(screen.getByRole('button', { name: /resolve without fixing/i }));

    await waitFor(() => expect(adminHeldImportApi.resolve).toHaveBeenCalledWith(
      heldRow.id, 'scanned image, no text layer'));
  });

  it('lets an operator with an admin role download the held statement', async () => {
    vi.mocked(adminHeldImportApi.download).mockResolvedValue(undefined);
    mockAuth(['IMPORT_TRIAGE_MANAGE'], ['ADMIN']);
    renderPage();
    await screen.findByText('hdfc-june.pdf');
    await userEvent.click(screen.getByRole('button', { name: /details/i }));
    await screen.findByText(/no header row found/);

    await userEvent.click(screen.getByRole('button', { name: /download statement/i }));

    await waitFor(() => expect(adminHeldImportApi.download)
      .toHaveBeenCalledWith(heldRow.id, heldRow.fileName));
  });

  /** Mirrors HeldStatementDetail's identical rule and identical reasoning: a role that can work
   *  the queue must not see a control for an action the backend would refuse anyway. */
  it('hides the download control from a non-admin role', async () => {
    mockAuth(['IMPORT_TRIAGE_MANAGE'], ['SUPPORT']);
    renderPage();
    await screen.findByText('hdfc-june.pdf');
    await userEvent.click(screen.getByRole('button', { name: /details/i }));
    await screen.findByText(/no header row found/);

    expect(screen.queryByRole('button', { name: /download statement/i })).not.toBeInTheDocument();
  });

  it('shows the waiting and reprocessing counts separately', async () => {
    mockAuth(['IMPORT_TRIAGE_MANAGE']);
    renderPage();

    expect(await screen.findByText('3')).toBeInTheDocument();
    expect(screen.getByText('1')).toBeInTheDocument();
  });
});
