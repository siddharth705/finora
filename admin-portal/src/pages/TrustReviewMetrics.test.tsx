import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import TrustReviewMetrics from './TrustReviewMetrics';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminHeldStatementApi } from '../api/endpoints';

vi.mock('../context/ThemeContext', () => ({
  useTheme: () => ({ theme: 'system', resolvedTheme: 'light', setTheme: vi.fn() }),
}));
vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
  adminHeldStatementApi: {
    telemetry: vi.fn(),
  },
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <TrustReviewMetrics />
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

describe('TrustReviewMetrics', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('is gated on PLATFORM_DIAGNOSTICS_VIEW', () => {
    mockAuth(['TRUST_REVIEW_MANAGE'], ['ADMIN']); // has queue access, not diagnostics
    renderPage();

    expect(screen.getByText(/don't have access to this section/i)).toBeInTheDocument();
  });

  it('shows the total, resolution, and false-positive counts', async () => {
    vi.mocked(adminHeldStatementApi.telemetry).mockResolvedValue({
      totalHolds: 12, resolved: 8, approved: 6, rejected: 2, falsePositives: 2,
      byCategory: { COUNT_MISMATCH: 7, PERIOD_INTEGRITY: 1 },
      medianResolutionHours: 4.5,
    });
    mockAuth(['PLATFORM_DIAGNOSTICS_VIEW'], ['ADMIN']);
    renderPage();

    expect(await screen.findByText('12')).toBeInTheDocument();
    expect(screen.getByText('8')).toBeInTheDocument();
    expect(screen.getByText(/COUNT_MISMATCH/)).toBeInTheDocument();
  });

  it('shows a category placeholder for a hold that predates category tracking, not a broken number', async () => {
    // totalHolds: 1 with an empty byCategory is a real, reachable state -- a hold created before
    // Task 1's migration, which was never snapshotted with a category. It is deliberately NOT the
    // same as "zero holds" (the tile above still correctly shows 1), so the placeholder text must
    // not claim a specific cause ("no holds resolved yet") that isn't the one this scenario has.
    vi.mocked(adminHeldStatementApi.telemetry).mockResolvedValue({
      totalHolds: 1, resolved: 0, approved: 0, rejected: 0, falsePositives: 0,
      byCategory: {}, medianResolutionHours: null,
    });
    mockAuth(['PLATFORM_DIAGNOSTICS_VIEW'], ['ADMIN']);
    renderPage();

    await screen.findByText('1');
    expect(screen.getByText(/no trust-condition data recorded yet/i)).toBeInTheDocument();
  });

  it('shows an em-dash for median resolution when nothing has resolved yet', async () => {
    vi.mocked(adminHeldStatementApi.telemetry).mockResolvedValue({
      totalHolds: 1, resolved: 0, approved: 0, rejected: 0, falsePositives: 0,
      byCategory: { COUNT_MISMATCH: 1 }, medianResolutionHours: null,
    });
    mockAuth(['PLATFORM_DIAGNOSTICS_VIEW'], ['ADMIN']);
    renderPage();

    expect(await screen.findByText('—')).toBeInTheDocument();
  });
});
