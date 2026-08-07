import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import ImportTrace from './ImportTrace';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminImportTraceApi } from '../api/endpoints';
import type { ImportTrace as Trace } from '../types';

vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
  adminImportTraceApi: {
    byAnalysis: vi.fn(),
    byJob: vi.fn(),
  },
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ImportTrace />
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

/** A queued import that staged successfully and has not been confirmed — the common shape. */
function trace(over: Partial<Trace> = {}): Trace {
  return {
    analysisReference: 'SA-20260806-0145',
    importJobId: '0f8b1c2d-0000-0000-0000-000000000001',
    importSessionId: '0f8b1c2d-0000-0000-0000-000000000002',
    correlationId: 'worker-abc123',
    analysis: {
      analysis: {
        reference: 'SA-20260806-0145',
        sourceFormat: 'PDF',
        layoutFingerprint: 'fp-hdfc-7c1',
        outcome: 'PARSED',
        failureCode: null,
        sectionCount: 1,
        rowCount: 128,
        unanchoredReasons: {},
        unanchoredRowCount: 0,
        durationMs: 2400,
        byteSize: 90210,
        createdAt: '2026-08-06T09:00:00Z',
      },
      timesLayoutSeen: 4,
      timesLayoutFailed: 0,
    },
    job: {
      status: 'COMPLETED',
      attemptCount: 1,
      rowsTotal: 128,
      rowsProcessed: 128,
      lastError: null,
      queuedAt: '2026-08-06T09:00:00Z',
      startedAt: '2026-08-06T09:00:01Z',
      finishedAt: '2026-08-06T09:00:04Z',
      totalDurationMs: 4000,
    },
    stages: [
      { stage: 'PARSING', attempt: 1, outcome: 'COMPLETED', startedAt: '2026-08-06T09:00:01Z', endedAt: '2026-08-06T09:00:02Z', durationMs: 900 },
      { stage: 'IMPORTING', attempt: 1, outcome: 'SKIPPED', startedAt: '2026-08-06T09:00:04Z', endedAt: '2026-08-06T09:00:04Z', durationMs: 0 },
    ],
    verification: [
      { sectionIndex: 0, rule: 'BALANCE_CHAIN', outcome: 'VERIFIED', details: { checked: 128 }, recordedAt: '2026-08-06T09:00:03Z' },
    ],
    learning: { events: 3, byStatus: { COMPLETED: 3 }, outstanding: [] },
    completion: { statementImportId: null, transactionsImported: null, transactionsSkipped: null, importedAt: null, sessionConfirmedAt: null },
    ...over,
  };
}

async function traceFor(handle = 'SA-20260806-0145') {
  const user = userEvent.setup();
  renderPage();
  await user.type(screen.getByLabelText('Reference or job id'), handle);
  await user.click(screen.getByRole('button', { name: /trace/i }));
  return user;
}

beforeEach(() => {
  mockAuth();
  vi.mocked(adminImportTraceApi.byAnalysis).mockReset().mockResolvedValue(trace());
  vi.mocked(adminImportTraceApi.byJob).mockReset().mockResolvedValue(trace());
});

describe('Import Trace — one import, end to end', () => {
  it('assembles the blocks that were previously three separate queries', async () => {
    await traceFor();

    // Queue, parsing, verification and learning in one view -- the criterion is that an operator
    // does not have to know that three tables exist, let alone join them by hand.
    expect(await screen.findByText('Queue')).toBeInTheDocument();
    expect(screen.getByText('Parsing')).toBeInTheDocument();
    expect(screen.getByText('Verification')).toBeInTheDocument();
    expect(screen.getByText('Learning')).toBeInTheDocument();
    expect(screen.getByText('BALANCE_CHAIN')).toBeInTheDocument();
    expect(screen.getByText('fp-hdfc-7c1')).toBeInTheDocument();
  });

  it('reaches the same trace from a job id as from a reference', async () => {
    // A support conversation produces a reference; the queue produces a job id. Neither is the
    // "real" handle, which is why there are two routes and no guessing.
    const user = userEvent.setup();
    renderPage();
    await user.selectOptions(screen.getByLabelText('Handle'), 'job');
    await user.type(screen.getByLabelText('Reference or job id'), '0f8b1c2d-0000-0000-0000-000000000001');
    await user.click(screen.getByRole('button', { name: /trace/i }));

    await waitFor(() => expect(adminImportTraceApi.byJob).toHaveBeenCalled());
    expect(adminImportTraceApi.byAnalysis).not.toHaveBeenCalled();
  });
});

describe('Import Trace — an absent block is an answer', () => {
  it('says a synchronous import had no job rather than rendering an empty panel', async () => {
    vi.mocked(adminImportTraceApi.byAnalysis).mockResolvedValue(trace({ job: null, stages: [] }));
    await traceFor();

    // "This path does not record that" and "that did not happen" are different facts, and blank
    // space says neither.
    expect(await screen.findByText(/ran synchronously, so it never had a job/i)).toBeInTheDocument();
    expect(screen.getByText(/synchronous path is not separately timed/i)).toBeInTheDocument();
  });

  it('does not let an unconfirmed import read as a failed one', async () => {
    // Staging successfully and importing are different events. A completion panel showing zeroes
    // would say the import landed and imported nothing.
    await traceFor();

    expect(await screen.findByText(/Nothing was confirmed/i)).toBeInTheDocument();
    expect(screen.getByText(/confirming is still the user’s decision/i)).toBeInTheDocument();
  });

  it('distinguishes "no verification recorded" from "everything passed"', async () => {
    vi.mocked(adminImportTraceApi.byAnalysis).mockResolvedValue(trace({ verification: [] }));
    await traceFor();

    expect(await screen.findByText(/not the same as every rule passing/i)).toBeInTheDocument();
  });

  it('reports an uncounted job as uncounted rather than as zero rows', async () => {
    vi.mocked(adminImportTraceApi.byAnalysis).mockResolvedValue(trace({
      job: { ...trace().job!, status: 'PARSING', rowsTotal: null, rowsProcessed: 0 },
    }));
    await traceFor();

    expect(await screen.findByText('Not counted')).toBeInTheDocument();
    expect(screen.queryByText('0 of 0')).not.toBeInTheDocument();
  });
});

describe('Import Trace — reports, does not judge', () => {
  it('shows where a worker died without calling the import unhealthy', async () => {
    vi.mocked(adminImportTraceApi.byAnalysis).mockResolvedValue(trace({
      stages: [
        { stage: 'ANALYZING', attempt: 1, outcome: 'RUNNING', startedAt: '2026-08-06T09:00:01Z', endedAt: null, durationMs: null },
      ],
    }));
    await traceFor();

    // The one inference the page does draw, because an operator would otherwise have to know that
    // RUNNING on a finished job means a dead worker.
    expect(await screen.findByText(/worker died here/i)).toBeInTheDocument();
  });

  it('carries no overall verdict, score or health badge', async () => {
    await traceFor();
    await screen.findByText('Queue');

    // The position VerificationReport and LayoutIntelligence both take: a summary judgement needs a
    // weighting policy nothing here can calibrate, and a number that quietly becomes a verdict is
    // the failure mode those were written to avoid.
    expect(screen.queryByText(/healthy|unhealthy|overall (status|score)|looks fine/i)).not.toBeInTheDocument();
  });
});

describe('Import Trace — access', () => {
  it('is gated on the same read-only permission as its sibling diagnostics screens', async () => {
    mockAuth([]);
    renderPage();

    expect(screen.queryByRole('button', { name: /trace/i })).not.toBeInTheDocument();
    expect(adminImportTraceApi.byAnalysis).not.toHaveBeenCalled();
  });
});
