import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { NotificationBell } from './NotificationBell';
import { useDashboardOverview } from '../hooks/useDashboardOverview';
import type { OperationalDashboardDto } from '../types';

vi.mock('../hooks/useDashboardOverview', () => ({
  useDashboardOverview: vi.fn(),
}));

function overview(overrides: Partial<OperationalDashboardDto> = {}): OperationalDashboardDto {
  return {
    totalUsers: 0,
    activeUsersToday: 0,
    transactionsToday: 0,
    importsToday: 0,
    importsWithSkippedRowsToday: 0,
    inactiveUsersLast7Days: 0,
    previousDay: { activeUsers: 0, transactions: 0, imports: 0, importsWithSkippedRows: 0 },
    needsAttention: { importsWithSkippedRowsToday: 0, lockedAccounts: 0, transactionsNeedingCategoryReview: 0, transactionsFlaggedAsDuplicates: 0 },
    health: { overallStatus: 'UP', providers: [] },
    alerts: [],
    recentActivity: [],
    ...overrides,
  };
}

function renderBell() {
  return render(
    <MemoryRouter>
      <NotificationBell />
    </MemoryRouter>
  );
}

describe('NotificationBell', () => {
  beforeEach(() => {
    vi.mocked(useDashboardOverview).mockReset();
  });

  it('renders nothing while overview data is unavailable (loading, or no PLATFORM_STATS_VIEW)', () => {
    vi.mocked(useDashboardOverview).mockReturnValue({ data: undefined } as never);

    const { container } = renderBell();

    expect(container).toBeEmptyDOMElement();
  });

  it('shows no badge when there is nothing to flag', () => {
    vi.mocked(useDashboardOverview).mockReturnValue({ data: overview() } as never);

    renderBell();

    expect(screen.getByRole('button', { name: 'No alerts' })).toBeInTheDocument();
  });

  it('badges the count of health alerts plus non-zero needs-attention fields combined', () => {
    vi.mocked(useDashboardOverview).mockReturnValue({
      data: overview({
        alerts: [{ severity: 'critical', title: 'Database', detail: 'down' }],
        needsAttention: { importsWithSkippedRowsToday: 3, lockedAccounts: 2, transactionsNeedingCategoryReview: 0, transactionsFlaggedAsDuplicates: 0 },
      }),
    } as never);

    renderBell();

    // 1 alert + 2 non-zero needsAttention fields = 3 -- not the sum of the raw counts (1+3+2=6).
    expect(screen.getByRole('button', { name: '3 items need attention' })).toBeInTheDocument();
    expect(screen.getByText('3')).toBeInTheDocument();
  });

  it('caps the visible badge at "9+" without capping the accessible count', () => {
    vi.mocked(useDashboardOverview).mockReturnValue({
      data: overview({
        alerts: Array.from({ length: 12 }, (_, i) => ({ severity: 'warning' as const, title: `Alert ${i}`, detail: 'x' })),
      }),
    } as never);

    renderBell();

    expect(screen.getByText('9+')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '12 items need attention' })).toBeInTheDocument();
  });

  it('opens to a calm message when there is nothing to flag', () => {
    vi.mocked(useDashboardOverview).mockReturnValue({ data: overview() } as never);

    renderBell();
    fireEvent.click(screen.getByRole('button', { name: 'No alerts' }));

    expect(screen.getByText('Nothing needs attention right now.')).toBeInTheDocument();
  });

  it('opens to show both alerts and needs-attention rows, each with real counts', () => {
    vi.mocked(useDashboardOverview).mockReturnValue({
      data: overview({
        alerts: [{ severity: 'warning', title: 'Statement Import Pipeline', detail: 'high skip rate' }],
        needsAttention: { importsWithSkippedRowsToday: 0, lockedAccounts: 4, transactionsNeedingCategoryReview: 0, transactionsFlaggedAsDuplicates: 0 },
      }),
    } as never);

    renderBell();
    fireEvent.click(screen.getByRole('button', { name: '2 items need attention' }));

    expect(screen.getByText('Statement Import Pipeline')).toBeInTheDocument();
    expect(screen.getByText('high skip rate')).toBeInTheDocument();
    expect(screen.getByText('4')).toBeInTheDocument();
    expect(screen.getByText(/accounts are currently locked out/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Go to Users/i })).toHaveAttribute('href', '/users');
  });

  it('closes when clicking outside the bell', () => {
    vi.mocked(useDashboardOverview).mockReturnValue({
      data: overview({ alerts: [{ severity: 'critical', title: 'Database', detail: 'down' }] }),
    } as never);

    renderBell();
    fireEvent.click(screen.getByRole('button', { name: '1 item needs attention' }));
    expect(screen.getByText('Database')).toBeInTheDocument();

    fireEvent.mouseDown(document.body);

    expect(screen.queryByText('Database')).not.toBeInTheDocument();
  });
});
