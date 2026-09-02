import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import Reports from './Reports';
import { reportsApi, type ReportData } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  reportsApi: { availableMonths: vi.fn(), forMonth: vi.fn() },
}));

function report(overrides: Partial<ReportData> = {}): ReportData {
  return {
    month: '2026-08',
    income: 100000,
    expense: 40000,
    categories: [{ category: 'Food', amount: 25000 }],
    ...overrides,
  };
}

function pending<T>(): Promise<T> {
  return new Promise<T>(() => {});
}

function renderPage() {
  return render(
    <MemoryRouter>
      <Reports />
    </MemoryRouter>
  );
}

describe('Reports — loading states', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('announces the initial load immediately instead of rendering plain "Loading…" text', () => {
    vi.mocked(reportsApi.availableMonths).mockReturnValue(pending<string[]>());
    vi.mocked(reportsApi.forMonth).mockReturnValue(pending<ReportData>());

    renderPage();

    const region = screen.getByRole('status');
    expect(region).toHaveAttribute('aria-busy', 'true');
    expect(screen.getByText('Loading your reports')).toBeInTheDocument();
  });

  /**
   * The window the roadmap missed entirely. `loading` only ever covered availableMonths(); once
   * months resolved, the toolbar rendered and the `{report && …}` guard rendered NOTHING beneath it
   * for the whole first forMonth() call -- no figures, no skeleton, no text.
   */
  it('shows a loading region below the toolbar during the first month fetch, not a blank page', async () => {
    vi.mocked(reportsApi.availableMonths).mockResolvedValue(['2026-07', '2026-08']);
    vi.mocked(reportsApi.forMonth).mockReturnValue(pending<ReportData>());

    renderPage();

    // Toolbar is up (months resolved)...
    expect(await screen.findByLabelText('Month')).toBeInTheDocument();
    // ...and the body below it announces itself rather than being empty.
    expect(screen.getByText("Loading this month's report")).toBeInTheDocument();
  });

  /**
   * The headline gap. Switching month must keep the previous month's figures on screen with a
   * spinner -- NOT swap them for a skeleton, which is what feeding the raw loading flag into
   * useDelayedLoading would have done (its own doc comment forbids exactly that).
   */
  it('keeps the previous month visible with a Refreshing indicator while a new month loads', async () => {
    const user = userEvent.setup();
    vi.mocked(reportsApi.availableMonths).mockResolvedValue(['2026-07', '2026-08']);
    vi.mocked(reportsApi.forMonth).mockResolvedValueOnce(report({ month: '2026-08', expense: 40000 }));

    renderPage();
    expect(await screen.findByText('Food')).toBeInTheDocument();

    // Second month never resolves, so we can inspect the in-flight state.
    vi.mocked(reportsApi.forMonth).mockReturnValueOnce(pending<ReportData>());
    await user.selectOptions(screen.getByLabelText('Month'), '2026-07');

    expect(await screen.findByText('Refreshing…')).toBeInTheDocument();
    // The previous month's data is still there -- not replaced by a skeleton.
    expect(screen.getByText('Food')).toBeInTheDocument();
    expect(screen.queryByText("Loading this month's report")).not.toBeInTheDocument();
  });

  /**
   * The useAsyncGuard clear. `setReportLoading(false)` sits inside `if (isCurrent())` so a
   * superseded response cannot switch the indicator off while the request whose data is actually
   * awaited is still running. Nothing else in this file has two overlapping forMonth calls that
   * BOTH resolve, so without this the guard could be deleted with the suite still green.
   */
  it('ignores a superseded month response rather than overwriting or clearing the indicator', async () => {
    const user = userEvent.setup();
    vi.mocked(reportsApi.availableMonths).mockResolvedValue(['2026-07', '2026-08']);
    const deferred: Array<(r: ReportData) => void> = [];
    vi.mocked(reportsApi.forMonth).mockImplementation(
      () => new Promise<ReportData>((resolve) => { deferred.push(resolve); })
    );

    renderPage();

    await waitFor(() => expect(deferred).toHaveLength(1));
    deferred[0](report({ month: '2026-08', categories: [{ category: 'Food', amount: 4100 }] }));
    expect(await screen.findByText('Food')).toBeInTheDocument();

    // Away and straight back: request #1 (July) is abandoned, #2 (August) is the live one.
    await user.selectOptions(screen.getByLabelText('Month'), '2026-07');
    await user.selectOptions(screen.getByLabelText('Month'), '2026-08');
    await waitFor(() => expect(deferred).toHaveLength(3));
    expect(await screen.findByText('Refreshing…')).toBeInTheDocument();

    // The abandoned July response lands FIRST.
    deferred[1](report({ month: '2026-07', categories: [{ category: 'Transport', amount: 2100 }] }));
    await act(async () => { await Promise.resolve(); });

    expect(screen.queryByText('Transport')).not.toBeInTheDocument();
    expect(screen.getByText('Refreshing…')).toBeInTheDocument();

    // ...and only the live one settles the page.
    deferred[2](report({ month: '2026-08', categories: [{ category: 'Dining', amount: 1800 }] }));
    await waitFor(() => expect(screen.queryByText('Refreshing…')).not.toBeInTheDocument());
    expect(screen.getByText('Dining')).toBeInTheDocument();
  });

  it('disables Export CSV while a month switch is in flight, so it cannot export the month just left', async () => {
    const user = userEvent.setup();
    vi.mocked(reportsApi.availableMonths).mockResolvedValue(['2026-07', '2026-08']);
    vi.mocked(reportsApi.forMonth).mockResolvedValueOnce(report({ month: '2026-08' }));

    renderPage();
    await waitFor(() => expect(screen.getByRole('button', { name: /export csv/i })).toBeEnabled());

    vi.mocked(reportsApi.forMonth).mockReturnValueOnce(pending<ReportData>());
    await user.selectOptions(screen.getByLabelText('Month'), '2026-07');

    await screen.findByText('Refreshing…');
    expect(screen.getByRole('button', { name: /export csv/i })).toBeDisabled();
  });

  it('disables Export CSV until a report has actually loaded', async () => {
    vi.mocked(reportsApi.availableMonths).mockResolvedValue(['2026-08']);
    vi.mocked(reportsApi.forMonth).mockReturnValue(pending<ReportData>());

    renderPage();

    expect(await screen.findByRole('button', { name: /export csv/i })).toBeDisabled();
  });

  it('enables Export CSV once the report is in', async () => {
    vi.mocked(reportsApi.availableMonths).mockResolvedValue(['2026-08']);
    vi.mocked(reportsApi.forMonth).mockResolvedValue(report());

    renderPage();

    await waitFor(() => expect(screen.getByRole('button', { name: /export csv/i })).toBeEnabled());
  });
});
