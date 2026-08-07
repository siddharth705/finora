import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import LayoutIntelligence from './LayoutIntelligence';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminLayoutsApi } from '../api/endpoints';
import type { LayoutEvidenceReport, LayoutSummary, UnknownHeaderSummary } from '../types';

vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
  adminLayoutsApi: {
    overview: vi.fn(),
    drifting: vi.fn(),
    unknownHeaders: vi.fn(),
    evidence: vi.fn(),
    timeline: vi.fn(),
  },
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <LayoutIntelligence />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function mockAuth(permissions: string[] = ['PLATFORM_DIAGNOSTICS_VIEW']) {
  vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
    hasPermission: (p: string) => permissions.includes(p),
    permissions,
    fullName: 'Ops Admin',
  }));
}

const EVIDENCE: LayoutEvidenceReport = {
  totalImportsAnalysed: 42,
  distinctLayouts: 9,
  recurringLayouts: 3,
  importsOnRecurringLayouts: 12,
  medianDurationFirstEncounter: 1200,
  medianDurationRecurring: 1180,
  avgUnknownHeadersFirstEncounter: 1.5,
  avgUnknownHeadersRecurring: 1.5,
  avgSkippedRowsFirstEncounter: 0.25,
  avgSkippedRowsRecurring: 0.25,
  verdict: 'Recurring layouts import at effectively the same speed as first encounters. No performance case for layout reuse.',
};

const LAYOUT: LayoutSummary = {
  fingerprint: 'FP-1-A1B2C3D4',
  sourceFormat: 'PDF',
  columns: 6,
  usageCount: 4,
  firstSeen: '2026-05-01T00:00:00Z',
  lastSeen: '2026-08-01T00:00:00Z',
  stableCapabilities: ['RUNNING_BALANCE'],
  unstableCapabilities: ['DR_CR_SUFFIX'],
  unknownHeaders: ['Chq/Ref. No.'],
  medianDurationMs: 1180,
  totalRowsImported: 400,
  totalRowsSkipped: 1,
};

beforeEach(() => {
  vi.clearAllMocks();
  mockAuth();
  vi.mocked(adminLayoutsApi.evidence).mockResolvedValue(EVIDENCE);
  vi.mocked(adminLayoutsApi.overview).mockResolvedValue([LAYOUT]);
  vi.mocked(adminLayoutsApi.drifting).mockResolvedValue([]);
  vi.mocked(adminLayoutsApi.unknownHeaders).mockResolvedValue([]);
});

describe('LayoutIntelligence', () => {
  /**
   * The reason this page exists. The evidence report is what decides whether structural learning is
   * ever built (proposal §11, precondition 3), and until this page shipped it was computed by the
   * backend and readable by nobody.
   */
  it('renders the evidence verdict, which is the point of the page', async () => {
    renderPage();
    expect(await screen.findByText(/No performance case for layout reuse/)).toBeInTheDocument();
  });

  /**
   * A "no evidence" verdict is a SUCCESSFUL outcome, and the numbers behind it are near-identical
   * by construction. If the page only rendered the table, a reader would supply their own, more
   * encouraging conclusion -- which is exactly what the verdict text exists to prevent.
   */
  it('shows the first-encounter and recurrence figures side by side', async () => {
    renderPage();
    expect(await screen.findByText('Median import duration')).toBeInTheDocument();
    expect(screen.getByText('Imports analysed')).toBeInTheDocument();
    expect(screen.getByText('42')).toBeInTheDocument();
  });

  /**
   * The backend omits a figure when there is not enough data to compute it, and renders it as null.
   * A UI that fell back with `?? 0` would print "0 ms" and close the question with a number nobody
   * earned -- EvidenceReport's own doc comment calls that out as worse than no report at all.
   */
  it('renders an unmeasured figure as "Not measured", never as zero', async () => {
    vi.mocked(adminLayoutsApi.evidence).mockResolvedValue({
      ...EVIDENCE,
      medianDurationFirstEncounter: null,
      medianDurationRecurring: null,
      avgSkippedRowsFirstEncounter: null,
      avgSkippedRowsRecurring: null,
      verdict: 'Too few have a recorded duration to compare.',
    });
    renderPage();

    await screen.findByText(/Too few have a recorded duration/);
    expect(screen.getAllByText('Not measured').length).toBeGreaterThanOrEqual(4);
    expect(screen.queryByText('0 ms')).not.toBeInTheDocument();
    expect(screen.queryByText('0.00')).not.toBeInTheDocument();
  });

  it('lists layouts with their unstable capabilities', async () => {
    renderPage();
    expect(await screen.findByText('FP-1-A1B2C3D4')).toBeInTheDocument();
    expect(screen.getByText('DR_CR_SUFFIX')).toBeInTheDocument();
  });

  /** A single observation is not a trend, and the UI has to say so rather than showing a bare 1. */
  it('marks a layout seen only once', async () => {
    vi.mocked(adminLayoutsApi.overview).mockResolvedValue([{ ...LAYOUT, usageCount: 1 }]);
    renderPage();
    expect(await screen.findByText(/seen once/)).toBeInTheDocument();
  });

  /**
   * layoutCount > 1 is the signal worth acting on: a header spanning several DISTINCT layouts is a
   * gap in the parser's hint lists rather than one bank's quirk. That distinction is the whole
   * value of this tab, so it is stated in the row rather than left to be inferred from a number.
   */
  it('distinguishes an unknown header that spans layouts from one that does not', async () => {
    const spanning: UnknownHeaderSummary = {
      header: 'Value Dt', importCount: 12, layoutCount: 4,
      fingerprints: ['FP-1-A', 'FP-1-B'], firstSeen: '2026-05-01T00:00:00Z', lastSeen: '2026-08-01T00:00:00Z',
    };
    const isolated: UnknownHeaderSummary = { ...spanning, header: 'Cheque Img', layoutCount: 1, fingerprints: ['FP-1-A'] };
    vi.mocked(adminLayoutsApi.unknownHeaders).mockResolvedValue([spanning, isolated]);

    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: /Unknown headers/ }));

    expect(await screen.findByText('Value Dt')).toBeInTheDocument();
    expect(screen.getByText(/Spans layouts/)).toBeInTheDocument();
    expect(screen.getByText('Single layout')).toBeInTheDocument();
  });

  /** Drift says "this changed", never "this is wrong" -- the empty state has to read that way too. */
  it('shows the drifting tab with no false alarm when nothing changed', async () => {
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: /Drifting/ }));
    expect(await screen.findByText(/No layout has changed structurally/)).toBeInTheDocument();
  });

  it('degrades to the rest of the page when the evidence report fails', async () => {
    vi.mocked(adminLayoutsApi.evidence).mockRejectedValue(new Error('boom'));
    renderPage();
    expect(await screen.findByText(/Couldn't load the evidence report/)).toBeInTheDocument();
    // The layout table is an independent query and must still render.
    expect(await screen.findByText('FP-1-A1B2C3D4')).toBeInTheDocument();
  });

  it('is gated on PLATFORM_DIAGNOSTICS_VIEW', async () => {
    mockAuth([]);
    renderPage();
    await waitFor(() => {
      expect(screen.queryByText(/Is layout reuse worth building/)).not.toBeInTheDocument();
    });
  });
});
