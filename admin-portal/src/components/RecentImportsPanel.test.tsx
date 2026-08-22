import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RecentImportsPanel } from './RecentImportsPanel';
import { adminSystemApi } from '../api/endpoints';

// Row rendering itself (filename, user, skipped-count, empty state) is already covered by
// SystemHealth.test.tsx, which exercises this same component with no limit/viewAllTo. This file
// covers only what's new here: the `limit` truncation and the `viewAllTo` link, since Dashboard
// is the first caller to use either.
vi.mock('../api/endpoints', () => ({
  adminSystemApi: { recentImports: vi.fn() },
}));

function renderPanel(props: Parameters<typeof RecentImportsPanel>[0] = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <RecentImportsPanel {...props} />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function importRow(id: string, fileName: string) {
  return {
    id, userId: `user-${id}`, userEmail: `${id}@example.com`, fileName,
    transactionsImported: 5, transactionsSkipped: 0, hadSkippedRows: false,
    importedAt: new Date().toISOString(),
  };
}

describe('RecentImportsPanel', () => {
  beforeEach(() => {
    vi.mocked(adminSystemApi.recentImports).mockReset();
  });

  it('renders every row when no limit is given', async () => {
    vi.mocked(adminSystemApi.recentImports).mockResolvedValue([
      importRow('1', 'a.csv'), importRow('2', 'b.csv'), importRow('3', 'c.csv'),
    ]);

    renderPanel();

    await waitFor(() => expect(screen.getByText('a.csv')).toBeInTheDocument());
    expect(screen.getByText('b.csv')).toBeInTheDocument();
    expect(screen.getByText('c.csv')).toBeInTheDocument();
  });

  it('truncates to the given limit, client-side', async () => {
    vi.mocked(adminSystemApi.recentImports).mockResolvedValue([
      importRow('1', 'a.csv'), importRow('2', 'b.csv'), importRow('3', 'c.csv'),
    ]);

    renderPanel({ limit: 2 });

    await waitFor(() => expect(screen.getByText('a.csv')).toBeInTheDocument());
    expect(screen.getByText('b.csv')).toBeInTheDocument();
    expect(screen.queryByText('c.csv')).not.toBeInTheDocument();
  });

  it('shows a "View all" link only when viewAllTo is given', async () => {
    vi.mocked(adminSystemApi.recentImports).mockResolvedValue([]);

    renderPanel({ viewAllTo: '/health' });
    await waitFor(() => expect(screen.getByText('No statement imports recorded yet.')).toBeInTheDocument());
    expect(screen.getByRole('link', { name: /view all/i })).toHaveAttribute('href', '/health');
  });

  it('renders no "View all" link when viewAllTo is omitted', async () => {
    vi.mocked(adminSystemApi.recentImports).mockResolvedValue([]);

    renderPanel();
    await waitFor(() => expect(screen.getByText('No statement imports recorded yet.')).toBeInTheDocument());
    expect(screen.queryByRole('link', { name: /view all/i })).not.toBeInTheDocument();
  });
});
