import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import HeldStatementDetail from './HeldStatementDetail';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminHeldStatementApi } from '../api/endpoints';
import type { HeldStatementDetail as HeldStatementDetailDto, HeldStatementRow } from '../types';

vi.mock('../context/ThemeContext', () => ({
  useTheme: () => ({ theme: 'system', resolvedTheme: 'light', setTheme: vi.fn() }),
}));
vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
  adminHeldStatementApi: {
    get: vi.fn(),
    approve: vi.fn(),
    reject: vi.fn(),
    assign: vi.fn(),
    investigate: vi.fn(),
    notes: vi.fn(),
    download: vi.fn(),
  },
}));

const summary: HeldStatementRow = {
  id: '11111111-1111-1111-1111-111111111111',
  heldId: 'HLD-2026-100001',
  importJobId: '33333333-3333-3333-3333-333333333333',
  userId: '22222222-2222-2222-2222-222222222222',
  bankName: 'HDFC Bank',
  status: 'HELD',
  triggerSummary: 'Printed and parsed transaction count disagree (SUMMARY_TOTALS)',
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

const detail: HeldStatementDetailDto = {
  summary,
  findings: [
    {
      sectionIndex: 0,
      rule: 'SUMMARY_TOTALS',
      outcome: 'FAILED',
      details: { printedCreditCount: 80, parsedCreditCount: 79 },
      createdAt: '2026-09-01T07:59:00Z',
    },
  ],
  timeline: [
    { eventType: 'HELD_CREATED', fromStatus: null, toStatus: 'HELD', notes: 'counts disagree', actorId: null, createdAt: '2026-09-01T08:00:00Z' },
    { eventType: 'ASSIGNED', fromStatus: 'HELD', toStatus: 'ASSIGNED', notes: null, actorId: '44444444-4444-4444-4444-444444444444', createdAt: '2026-09-01T09:00:00Z' },
  ],
};

function renderPage(heldId = 'HLD-2026-100001') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/held-statements/${heldId}`]}>
        <Routes>
          <Route path="/held-statements/:heldId" element={<HeldStatementDetail />} />
        </Routes>
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

describe('HeldStatementDetail', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(adminHeldStatementApi.get).mockResolvedValue(detail);
  });

  it('is gated on TRUST_REVIEW_MANAGE', () => {
    mockAuth(['IMPORT_TRIAGE_MANAGE'], ['ADMIN']);
    renderPage();

    expect(screen.getByText(/don't have access to this section/i)).toBeInTheDocument();
    expect(adminHeldStatementApi.get).not.toHaveBeenCalled();
  });

  /** The operator has to see the numbers, not our sentence about them. */
  it('shows the printed and parsed numbers, not just the summary sentence', async () => {
    mockAuth(['TRUST_REVIEW_MANAGE'], ['ADMIN']);
    renderPage();

    expect(await screen.findByText(/count disagree/i)).toBeInTheDocument();
    expect(screen.getByText('printedCreditCount')).toBeInTheDocument();
    expect(screen.getByText('80')).toBeInTheDocument();
    expect(screen.getByText('parsedCreditCount')).toBeInTheDocument();
    expect(screen.getByText('79')).toBeInTheDocument();
  });

  /** ROW_ACCOUNTING's droppedTransactionCandidateReasons detail is a nested reason-code-to-count
   *  object, not a primitive -- String(value) on an object renders the literal, useless
   *  "[object Object]". */
  it('renders a nested object detail value legibly rather than [object Object]', async () => {
    vi.mocked(adminHeldStatementApi.get).mockResolvedValue({
      ...detail,
      findings: [{
        sectionIndex: 0,
        rule: 'ROW_ACCOUNTING',
        outcome: 'WARNING',
        details: { droppedTransactionCandidateReasons: { PAGE_FOOTER_OR_CLOSING_MARKER: 3 } },
        createdAt: '2026-09-01T07:59:00Z',
      }],
    });
    mockAuth(['TRUST_REVIEW_MANAGE'], ['ADMIN']);
    renderPage();

    expect(await screen.findByText('droppedTransactionCandidateReasons')).toBeInTheDocument();
    expect(screen.queryByText('[object Object]')).not.toBeInTheDocument();
    expect(screen.getByText(/PAGE_FOOTER_OR_CLOSING_MARKER/)).toBeInTheDocument();
  });

  it('renders the audit timeline oldest first', async () => {
    mockAuth(['TRUST_REVIEW_MANAGE'], ['ADMIN']);
    renderPage();
    await screen.findByText(/count disagree/i);

    const events = screen.getAllByText(/^(HELD_CREATED|ASSIGNED)$/);
    expect(events.map((el) => el.textContent)).toEqual(['HELD_CREATED', 'ASSIGNED']);
  });

  it('shows the download control for an ADMIN role', async () => {
    mockAuth(['TRUST_REVIEW_MANAGE'], ['ADMIN']);
    renderPage();
    await screen.findByText(/count disagree/i);

    expect(screen.getByRole('button', { name: /download statement/i })).toBeInTheDocument();
  });

  /**
   * The repository owner's decision, 2026-09-04: the download is pinned to ADMIN/SUPER_ADMIN, not
   * merely to TRUST_REVIEW_MANAGE. An operator holding only the permission (able to reach this
   * page and work the queue) must not see a control for an action the backend would refuse anyway
   * -- rendering a button that always 403s is worse than not rendering one.
   */
  it('hides the download control from a non-admin role', async () => {
    mockAuth(['TRUST_REVIEW_MANAGE'], ['SUPPORT']);
    renderPage();
    await screen.findByText(/count disagree/i);

    expect(screen.queryByRole('button', { name: /download statement/i })).not.toBeInTheDocument();
  });

  /** A resolved hold's approve/reject controls are disabled and the state is named, not just
   *  silently non-functional. */
  it('disables approve and reject once the hold is resolved, naming the state', async () => {
    vi.mocked(adminHeldStatementApi.get).mockResolvedValue({
      ...detail,
      summary: { ...summary, status: 'IMPORTED' },
    });
    mockAuth(['TRUST_REVIEW_MANAGE'], ['ADMIN']);
    renderPage();
    await screen.findByText(/count disagree/i);

    expect(screen.getByRole('button', { name: /^approve$/i })).toBeDisabled();
    expect(screen.getByRole('button', { name: /^reject$/i })).toBeDisabled();
    expect(screen.getByText(/already imported/i)).toBeInTheDocument();
  });

  it('leaves approve and reject enabled on an open hold', async () => {
    mockAuth(['TRUST_REVIEW_MANAGE'], ['ADMIN']);
    renderPage();
    await screen.findByText(/count disagree/i);

    expect(screen.getByRole('button', { name: /^approve$/i })).toBeEnabled();
    expect(screen.getByRole('button', { name: /^reject$/i })).toBeEnabled();
  });

  it('shows a not-found message for an unknown held id', async () => {
    vi.mocked(adminHeldStatementApi.get).mockRejectedValue({
      response: { status: 404, data: { message: 'No such held statement.' } },
    });
    mockAuth(['TRUST_REVIEW_MANAGE'], ['ADMIN']);
    renderPage('HLD-2026-999999');

    expect(await screen.findByText(/no such held statement/i)).toBeInTheDocument();
  });
});
