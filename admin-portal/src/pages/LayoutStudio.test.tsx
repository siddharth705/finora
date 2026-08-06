import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import LayoutStudio from './LayoutStudio';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminStatementAnalysisApi, adminAnalysisRunApi } from '../api/endpoints';
import type {
  StatementAnalysisDto, StatementAnalysisDetailDto, StatementAnalysisSummaryDto,
} from '../types';

vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
  adminStatementAnalysisApi: { recent: vi.fn(), summary: vi.fn(), byReference: vi.fn() },
  adminAnalysisRunApi: { analyze: vi.fn() },
}));

const notifySuccess = vi.fn();
const notifyError = vi.fn();
vi.mock('../context/NotificationContext', () => ({
  useNotify: () => ({ success: notifySuccess, error: notifyError }),
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <LayoutStudio />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function mockAuth(permissions: string[]) {
  vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
    hasPermission: (p: string) => permissions.includes(p),
    permissions,
    fullName: 'Ops Admin',
    logout: vi.fn(),
  }));
}

const SUMMARY: StatementAnalysisSummaryDto = {
  analysesInWindow: 3,
  totalAnalysesEver: 42,
  parsed: 38,
  failed: 4,
  distinctLayouts: 7,
  rowsExtractedInWindow: 822,
  unanchoredRowsInWindow: 689,
  unanchoredReasons: { NO_DATE_IN_ANCHOR_COLUMN: 640, AMOUNT_UNPARSEABLE: 49 },
};

/** A document read successfully. */
const PARSED: StatementAnalysisDto = {
  reference: 'SA-20260806-0145',
  sourceFormat: 'PDF',
  layoutFingerprint: 'FP-1-7A91D3C2',
  outcome: 'PARSED',
  failureCode: null,
  sectionCount: 1,
  rowCount: 569,
  unanchoredReasons: { NO_DATE_IN_ANCHOR_COLUMN: 649 },
  unanchoredRowCount: 649,
  durationMs: 812,
  byteSize: 204800,
  createdAt: '2026-08-06T10:15:00Z',
};

/** A document that never opened -- no fingerprint, and a row count that was never measured. */
const LOCKED: StatementAnalysisDto = {
  reference: 'SA-20260806-0146',
  sourceFormat: 'PDF',
  layoutFingerprint: null,
  outcome: 'FAILED',
  failureCode: 'IMPORT_008',
  sectionCount: null,
  rowCount: null,
  unanchoredReasons: {},
  unanchoredRowCount: 0,
  durationMs: 40,
  byteSize: 1024,
  createdAt: '2026-08-06T10:16:00Z',
};

const DETAIL: StatementAnalysisDetailDto = {
  analysis: PARSED,
  timesLayoutSeen: 12,
  timesLayoutFailed: 11,
};

/** A parse failure: 200 with a FAILED analysis, not an HTTP error -- see the backend controller. */
const ENCRYPTED_DETAIL: StatementAnalysisDetailDto = {
  analysis: { ...LOCKED, failureCode: 'IMPORT_008' },
  timesLayoutSeen: 0,
  timesLayoutFailed: 0,
};

beforeEach(() => {
  vi.clearAllMocks();
  mockAuth(['PLATFORM_DIAGNOSTICS_VIEW', 'ENGINE_ANALYSIS_RUN']);
  vi.mocked(adminStatementAnalysisApi.summary).mockResolvedValue(SUMMARY);
  vi.mocked(adminStatementAnalysisApi.recent).mockResolvedValue([PARSED, LOCKED]);
  vi.mocked(adminStatementAnalysisApi.byReference).mockResolvedValue(DETAIL);
});

describe('LayoutStudio', () => {
  it('shows the engine summary and the dominant unanchored reason first', async () => {
    renderPage();

    expect(await screen.findByText('FP-1-7A91D3C2')).toBeInTheDocument();
    expect(screen.getByText('822')).toBeInTheDocument();

    // Order matters, not just presence: the dominant reason is the one that decides whether a
    // capability is worth building, and a test that only asserted both names would pass with the
    // list reversed.
    const reasons = screen.getAllByText(/no date in anchor column|amount unparseable/);
    expect(reasons[0]).toHaveTextContent('no date in anchor column');
    expect(reasons[1]).toHaveTextContent('amount unparseable');
  });

  it('renders a never-measured row count as absent rather than as zero', async () => {
    // The distinction the whole diagnostics change was built to preserve. Rendering null as 0 would
    // make a document that never opened look like a document the parser read and found empty --
    // which sends someone hunting a parser capability when the answer was a wrong password.
    renderPage();

    const lockedRow = (await screen.findByRole('button', { name: 'SA-20260806-0146' })).closest('tr');
    expect(lockedRow).not.toBeNull();
    const cells = within(lockedRow as HTMLElement).getAllByRole('cell');
    expect(cells[2]).toHaveTextContent('—');
    expect(cells[2]).not.toHaveTextContent('0');
  });

  it('opens one analysis and shows how often its layout has already failed', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: 'SA-20260806-0145' }));

    await waitFor(() => {
      expect(adminStatementAnalysisApi.byReference).toHaveBeenCalledWith('SA-20260806-0145');
    });
    const history = await screen.findByRole('heading', { name: 'This layout' });
    const section = history.closest('section');
    expect(within(section as HTMLElement).getByText('12')).toBeInTheDocument();
    expect(within(section as HTMLElement).getByText('11')).toBeInTheDocument();
  });

  it('names the fields it cannot show instead of rendering empty placeholders for them', async () => {
    // The page's central rule. A tile reading "Parser version: 0" or "Verification: —" looks like a
    // measurement and gets acted on; naming the gap does not.
    renderPage();

    expect(await screen.findByRole('heading', { name: 'Not recorded yet' })).toBeInTheDocument();
    expect(screen.getByText('Parser version')).toBeInTheDocument();
    expect(screen.getByText('Verification findings')).toBeInTheDocument();
    expect(screen.getByText('Approval state')).toBeInTheDocument();
  });

  it('keeps raw evidence collapsed until asked for', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: 'SA-20260806-0145' }));
    const toggle = await screen.findByRole('button', { name: /raw evidence/i });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');

    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByText(/"timesLayoutSeen": 12/)).toBeInTheDocument();
  });

  it('says so when the evidence itself cannot be loaded', async () => {
    // The one state a diagnostics page must never render as a blank screen: its own failure.
    vi.mocked(adminStatementAnalysisApi.summary).mockRejectedValue(new Error('boom'));
    vi.mocked(adminStatementAnalysisApi.recent).mockRejectedValue(new Error('boom'));
    renderPage();

    expect(await screen.findByText(/couldn't load statement analyses/i)).toBeInTheDocument();
  });

  it('tells an admin the table is empty rather than showing nothing at all', async () => {
    vi.mocked(adminStatementAnalysisApi.recent).mockResolvedValue([]);
    renderPage();

    expect(await screen.findByText(/no statements have been analysed yet/i)).toBeInTheDocument();
  });

  it('is gated on the diagnostics permission', async () => {
    mockAuth([]);
    renderPage();

    await waitFor(() => {
      expect(adminStatementAnalysisApi.recent).not.toHaveBeenCalled();
    });
    expect(screen.queryByText('FP-1-7A91D3C2')).not.toBeInTheDocument();
  });

  describe('analysing a document', () => {
    function pdf(name = 'statement.pdf') {
      return new File(['%PDF-1.4 pretend'], name, { type: 'application/pdf' });
    }

    it('runs the engine and opens the analysis it produced', async () => {
      const user = userEvent.setup();
      vi.mocked(adminAnalysisRunApi.analyze).mockResolvedValue(DETAIL);
      renderPage();

      await user.upload(await screen.findByLabelText(/statement \(pdf or csv\)/i), pdf());
      await user.click(screen.getByRole('button', { name: /^analyse$/i }));

      await waitFor(() => expect(adminAnalysisRunApi.analyze).toHaveBeenCalled());
      // The detail panel opening is the observable outcome -- an admin who just analysed something
      // should be looking at it, not hunting for it in the table.
      expect(await screen.findByRole('heading', { name: 'This layout' })).toBeInTheDocument();
    });

    it('asks for a password when the document turns out to be encrypted', async () => {
      // IMPORT_008 comes back as a 200 with a FAILED analysis, so nothing throws. The page has to
      // notice the failure CODE to offer the retry -- treating any 200 as success would leave the
      // admin staring at an empty analysis with no way forward.
      const user = userEvent.setup();
      vi.mocked(adminAnalysisRunApi.analyze).mockResolvedValue(ENCRYPTED_DETAIL);
      renderPage();

      await user.upload(await screen.findByLabelText(/statement \(pdf or csv\)/i), pdf('locked.pdf'));
      expect(screen.queryByLabelText(/document password/i)).not.toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: /^analyse$/i }));

      expect(await screen.findByLabelText(/document password/i)).toBeInTheDocument();
      expect(notifyError).toHaveBeenCalledWith(expect.stringMatching(/encrypted/i));
    });

    it('sends the password in the request body once given', async () => {
      const user = userEvent.setup();
      vi.mocked(adminAnalysisRunApi.analyze).mockResolvedValue(ENCRYPTED_DETAIL);
      renderPage();

      await user.upload(await screen.findByLabelText(/statement \(pdf or csv\)/i), pdf('locked.pdf'));
      await user.click(screen.getByRole('button', { name: /^analyse$/i }));
      await user.type(await screen.findByLabelText(/document password/i), 'letmein');
      await user.click(screen.getByRole('button', { name: /^analyse$/i }));

      expect(adminAnalysisRunApi.analyze).toHaveBeenLastCalledWith(expect.any(File), 'letmein');
    });

    it('hides the upload panel from someone who may only read the reports', async () => {
      // Running the engine is a separately grantable permission (V61) precisely because viewing
      // diagnostics is documented as read-only. The UI has to honour that split, not just the API.
      mockAuth(['PLATFORM_DIAGNOSTICS_VIEW']);
      renderPage();

      expect(await screen.findByText('FP-1-7A91D3C2')).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /^analyse$/i })).not.toBeInTheDocument();
    });
  });
});
