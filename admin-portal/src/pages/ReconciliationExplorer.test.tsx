import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import ReconciliationExplorer from './ReconciliationExplorer';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminReconciliationExplorerApi } from '../api/endpoints';
import type { ReconciliationExplorerTrace } from '../types';

vi.mock('../context/ThemeContext', () => ({
  useTheme: () => ({ theme: 'system', resolvedTheme: 'light', setTheme: vi.fn() }),
}));
vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
  adminReconciliationExplorerApi: {
    trace: vi.fn(),
  },
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ReconciliationExplorer />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function mockAuth(permissions: string[] = ['RECONCILIATION_VIEW']) {
  vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
    hasPermission: (p: string) => permissions.includes(p),
    permissions,
    fullName: 'Ops Admin',
  }));
}

function trace(over: Partial<ReconciliationExplorerTrace> = {}): ReconciliationExplorerTrace {
  return {
    raw: {
      transactionId: '0f8b1c2d-0000-0000-0000-000000000001',
      description: 'REFUND ZOMATO 340.00',
      amount: 340,
      txnType: 'INCOME',
      txnDate: '2026-07-10',
      source: 'CSV_IMPORT',
    },
    normalized: { merchant: 'Zomato', categoryName: 'Dining' },
    edges: [{
      edgeId: '0f8b1c2d-0000-0000-0000-000000000002',
      counterpartTransactionId: '0f8b1c2d-0000-0000-0000-000000000003',
      relationshipType: 'REFUND',
      confidence: 91,
      sourceTrust: 95,
      status: 'AUTO_CONFIRMED',
      detectionMethod: 'RULE_ENGINE',
      explanation: { type: 'REFUND', sameMerchant: true },
    }],
    classification: {
      reconciliationStatus: 'REFUND',
      transactionExplanation: { type: 'REFUND', matchedTransaction: '0f8b1c2d-0000-0000-0000-000000000003' },
    },
    ...over,
  };
}

async function traceFor(id = '0f8b1c2d-0000-0000-0000-000000000001') {
  const user = userEvent.setup();
  renderPage();
  await user.type(screen.getByLabelText('Transaction id'), id);
  await user.click(screen.getByRole('button', { name: /trace/i }));
  return user;
}

beforeEach(() => {
  mockAuth();
  vi.mocked(adminReconciliationExplorerApi.trace).mockReset().mockResolvedValue(trace());
});

describe('Reconciliation Explorer — one transaction, raw to final classification', () => {
  it('assembles raw, normalized, matched and classification blocks in one view', async () => {
    await traceFor();

    expect(await screen.findByText('Raw')).toBeInTheDocument();
    expect(screen.getByText('Normalized')).toBeInTheDocument();
    expect(screen.getByText('Matched')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Confidence' })).toBeInTheDocument();
    expect(screen.getByText('Final classification')).toBeInTheDocument();
    expect(screen.getByText('REFUND ZOMATO 340.00')).toBeInTheDocument();
    expect(screen.getByText('Zomato')).toBeInTheDocument();
    expect(screen.getByText('Dining')).toBeInTheDocument();
  });

  it('shows the matched edge, including its own explanation', async () => {
    await traceFor();

    expect(await screen.findByText('0f8b1c2d-0000-0000-0000-000000000003')).toBeInTheDocument();
    expect(screen.getByText('91')).toBeInTheDocument();
    expect(screen.getByText(/sameMerchant=true/)).toBeInTheDocument();
  });

  it('flags a CANDIDATE edge as needing review', async () => {
    vi.mocked(adminReconciliationExplorerApi.trace).mockResolvedValue(trace({
      edges: [{ ...trace().edges[0], status: 'CANDIDATE', confidence: 63 }],
    }));
    await traceFor();

    expect(await screen.findByText(/needs review/i)).toBeInTheDocument();
  });
});

describe('Reconciliation Explorer — an absent edge is an answer', () => {
  it('distinguishes "never matched" from "not looked up" when there are no edges', async () => {
    vi.mocked(adminReconciliationExplorerApi.trace).mockResolvedValue(trace({ edges: [] }));
    await traceFor();

    expect(await screen.findByText(/no matched edges/i)).toBeInTheDocument();
    expect(screen.getByText(/nothing to score without a matched edge/i)).toBeInTheDocument();
  });

  it('says no explanation was recorded rather than rendering a blank panel', async () => {
    vi.mocked(adminReconciliationExplorerApi.trace).mockResolvedValue(trace({
      classification: { reconciliationStatus: 'OK', transactionExplanation: null },
    }));
    await traceFor();

    expect(await screen.findByText(/no explanation recorded/i)).toBeInTheDocument();
  });

  it('reads uncategorized rather than blank when there is no category', async () => {
    vi.mocked(adminReconciliationExplorerApi.trace).mockResolvedValue(trace({
      normalized: { merchant: null, categoryName: null },
    }));
    await traceFor();

    expect(await screen.findByText('Uncategorized')).toBeInTheDocument();
    expect(screen.getByText('Not resolved')).toBeInTheDocument();
  });
});

describe('Reconciliation Explorer — errors', () => {
  it('reports an unknown transaction id as not found', async () => {
    vi.mocked(adminReconciliationExplorerApi.trace).mockRejectedValue(new Error('not found'));
    await traceFor();

    expect(await screen.findByText(/no transaction with that id/i)).toBeInTheDocument();
  });
});

describe('Reconciliation Explorer — access', () => {
  it('is gated on RECONCILIATION_VIEW, same as the Reconciliation Monitor', async () => {
    mockAuth([]);
    renderPage();

    expect(screen.queryByRole('button', { name: /trace/i })).not.toBeInTheDocument();
    expect(adminReconciliationExplorerApi.trace).not.toHaveBeenCalled();
  });
});
