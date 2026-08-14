import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import ImportDetail from './ImportDetail';
import { importJobsApi, type ImportJobProgress, type ImportJobTimeline } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  importJobsApi: {
    progress: vi.fn(),
    timeline: vi.fn(),
  },
}));

const api = vi.mocked(importJobsApi);

const navigate = vi.fn();
vi.mock('react-router-dom', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-router-dom')>()),
  useNavigate: () => navigate,
}));

function job(overrides: Partial<ImportJobProgress> = {}): ImportJobProgress {
  return {
    jobId: 'job-1',
    fileName: 'hdfc-july.pdf',
    status: 'COMPLETED',
    rowsTotal: 42,
    rowsProcessed: 42,
    createdAt: '2026-08-12T10:00:00Z',
    startedAt: '2026-08-12T10:00:01Z',
    finishedAt: '2026-08-12T10:00:05Z',
    importSessionId: 'session-1',
    error: null,
    correlationId: null,
    ...overrides,
  };
}

function emptyTimeline(overrides: Partial<ImportJobTimeline> = {}): ImportJobTimeline {
  return {
    jobId: 'job-1',
    status: 'COMPLETED',
    failureCode: null,
    stages: [],
    ...overrides,
  };
}

function renderDetail(jobId = 'job-1') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/app/imports/${jobId}`]}>
        <Routes>
          <Route path="/app/imports/:jobId" element={<ImportDetail />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  navigate.mockReset();
  api.progress.mockReset();
  api.timeline.mockReset().mockResolvedValue(emptyTimeline());
});

describe('ImportDetail', () => {
  it('shows a not-found state when the job is not this user\'s (or does not exist)', async () => {
    // The endpoint answers ownership failures and missing jobs identically -- 404, not 403 -- so
    // this page cannot and does not try to tell the two apart.
    api.progress.mockRejectedValue({ response: { status: 404 } });
    renderDetail();

    expect(await screen.findByText('Import not found')).toBeInTheDocument();
    expect(screen.getByText(/back to statement history/i)).toBeInTheDocument();
  });

  it('renders the file name, status, and timeline for a completed import', async () => {
    api.progress.mockResolvedValue(job());
    api.timeline.mockResolvedValue(emptyTimeline({
      stages: [{ stage: 'PARSING', attempt: 1, outcome: 'COMPLETED', startedAt: '2026-08-12T10:00:01Z', endedAt: '2026-08-12T10:00:02Z', durationMs: 1000 }],
    }));
    renderDetail();

    expect(await screen.findByText('hdfc-july.pdf')).toBeInTheDocument();
    expect(screen.getByText(/ready to review/i)).toBeInTheDocument();
    expect(await screen.findByTestId('import-timeline')).toBeInTheDocument();
  });

  it('offers to review a completed import with a staged session', async () => {
    api.progress.mockResolvedValue(job({ status: 'COMPLETED', importSessionId: 'session-1' }));
    const user = userEvent.setup();
    renderDetail();

    const reviewButton = await screen.findByRole('button', { name: /review this import/i });
    await user.click(reviewButton);

    expect(navigate).toHaveBeenCalledWith('/app/import', { state: { kind: 'resume', resumeSessionId: 'session-1' } });
  });

  it('does not offer to review a completed import with no staged session left', async () => {
    api.progress.mockResolvedValue(job({ status: 'COMPLETED', importSessionId: null }));
    renderDetail();

    await screen.findByText('hdfc-july.pdf');
    expect(screen.queryByRole('button', { name: /review this import/i })).not.toBeInTheDocument();
  });

  it('offers to upload a different file for a failed import', async () => {
    api.progress.mockResolvedValue(job({ status: 'FAILED', importSessionId: null }));
    api.timeline.mockResolvedValue(emptyTimeline({ status: 'FAILED', failureCode: 'IMPORT_011' }));
    const user = userEvent.setup();
    renderDetail();

    const uploadButton = await screen.findByRole('button', { name: /upload a different file/i });
    await user.click(uploadButton);

    expect(navigate).toHaveBeenCalledWith('/app/import');
  });

  it('does not offer either action for a job still in progress', async () => {
    api.progress.mockResolvedValue(job({ status: 'ANALYZING', importSessionId: null, finishedAt: null }));
    api.timeline.mockResolvedValue(emptyTimeline({ status: 'ANALYZING' }));
    renderDetail();

    await screen.findByText('hdfc-july.pdf');
    expect(screen.queryByRole('button', { name: /review this import/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /upload a different file/i })).not.toBeInTheDocument();
  });

  it('re-fetches both progress and the timeline on manual refresh, without polling on its own', async () => {
    api.progress.mockResolvedValue(job({ status: 'ANALYZING', importSessionId: null }));
    api.timeline.mockResolvedValue(emptyTimeline({ status: 'ANALYZING' }));
    const user = userEvent.setup();
    renderDetail();

    await screen.findByText('hdfc-july.pdf');
    expect(api.progress).toHaveBeenCalledTimes(1);
    expect(api.timeline).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole('button', { name: /refresh/i }));

    await waitFor(() => expect(api.progress).toHaveBeenCalledTimes(2));
    expect(api.timeline).toHaveBeenCalledTimes(2);
  });

  /**
   * Bug fix, caught by independent review: the not-found guard used to check `isError` directly
   * (`progressQuery.isError || !progressQuery.data`), but React Query keeps the last-good `data`
   * around across a failed REFETCH rather than clearing it -- so a transient blip on this page's
   * only real interaction (the Refresh button) replaced a working, correctly-rendered import with
   * "This import doesn't exist, or isn't yours to view," which is actively wrong, not just an
   * unpolished error state. Fixed by checking `!progressQuery.data` alone -- true only when the
   * import has never loaded successfully at all.
   */
  it('keeps showing the loaded import when a manual refresh fails, instead of "not found"', async () => {
    api.progress.mockResolvedValueOnce(job());
    renderDetail();

    await screen.findByText('hdfc-july.pdf');

    api.progress.mockRejectedValueOnce(new Error('network blip'));
    await userEvent.setup().click(screen.getByRole('button', { name: /refresh/i }));

    await waitFor(() => expect(api.progress).toHaveBeenCalledTimes(2));
    expect(screen.getByText('hdfc-july.pdf')).toBeInTheDocument();
    expect(screen.queryByText('Import not found')).not.toBeInTheDocument();
    expect(screen.getByText(/couldn't refresh/i)).toBeInTheDocument();
  });
});
