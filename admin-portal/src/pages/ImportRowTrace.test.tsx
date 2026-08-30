import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import ImportRowTrace from './ImportRowTrace';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminImportRowTraceApi } from '../api/endpoints';
import type { ImportRowTrace as ImportRowTraceType } from '../types';

vi.mock('../context/ThemeContext', () => ({
  useTheme: () => ({ theme: 'system', resolvedTheme: 'light', setTheme: vi.fn() }),
}));
vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
  adminImportRowTraceApi: {
    trace: vi.fn(),
  },
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ImportRowTrace />
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

function trace(over: Partial<ImportRowTraceType> = {}): ImportRowTraceType {
  return {
    statementImportId: '0f8b1c2d-0000-0000-0000-000000000001',
    rows: [
      {
        rowPosition: 3,
        transactionId: '0f8b1c2d-0000-0000-0000-000000000002',
        description: 'ZOMATO ORDER',
        amount: 340,
        txnDate: '2026-07-10',
      },
    ],
    ...over,
  };
}

async function traceFor(id = '0f8b1c2d-0000-0000-0000-000000000001') {
  const user = userEvent.setup();
  renderPage();
  await user.type(screen.getByLabelText('Statement import id'), id);
  await user.click(screen.getByRole('button', { name: /trace/i }));
  return user;
}

beforeEach(() => {
  mockAuth();
  vi.mocked(adminImportRowTraceApi.trace).mockReset().mockResolvedValue(trace());
});

describe('Import Row Trace — a row and the transaction it became', () => {
  it('shows the row position next to the transaction it produced', async () => {
    await traceFor();

    expect(await screen.findByText('Rows')).toBeInTheDocument();
    expect(screen.getByText('3')).toBeInTheDocument();
    expect(screen.getByText('0f8b1c2d-0000-0000-0000-000000000002')).toBeInTheDocument();
    expect(screen.getByText('ZOMATO ORDER')).toBeInTheDocument();
  });
});

describe('Import Row Trace — no position data is an answer', () => {
  it('states that no position data is available rather than rendering an empty table', async () => {
    vi.mocked(adminImportRowTraceApi.trace).mockResolvedValue(trace({ rows: [] }));
    await traceFor();

    expect(await screen.findByText(/no position data available/i)).toBeInTheDocument();
  });
});

describe('Import Row Trace — errors', () => {
  it('reports an unknown statement import id as not found', async () => {
    vi.mocked(adminImportRowTraceApi.trace).mockRejectedValue(new Error('not found'));
    await traceFor();

    expect(await screen.findByText(/no statement import with that id/i)).toBeInTheDocument();
  });
});

describe('Import Row Trace — access', () => {
  it('is gated on PLATFORM_DIAGNOSTICS_VIEW, same as the Import Trace', async () => {
    mockAuth([]);
    renderPage();

    expect(screen.queryByRole('button', { name: /trace/i })).not.toBeInTheDocument();
    expect(adminImportRowTraceApi.trace).not.toHaveBeenCalled();
  });
});
