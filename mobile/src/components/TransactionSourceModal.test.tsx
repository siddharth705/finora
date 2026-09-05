import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { TransactionSourceModal } from './TransactionSourceModal';
import { transactionsApi } from '../api/endpoints';
import type { TransactionSource } from '../types';

jest.mock('../api/endpoints', () => ({
  transactionsApi: { source: jest.fn() },
}));

const transactions = transactionsApi as jest.Mocked<typeof transactionsApi>;

function source(over: Partial<TransactionSource> = {}): TransactionSource {
  return {
    available: true,
    sourceLabel: 'CSV_IMPORT',
    statementImportId: 'si-1',
    fileName: 'march-statement.pdf',
    rowPosition: 14,
    importedAt: '2026-08-15T10:00:00Z',
    accountName: 'HDFC Savings',
    statementPeriodStart: '2026-03-01',
    statementPeriodEnd: '2026-03-31',
    ...over,
  };
}

function renderModal(transactionId: string | null, onClose = jest.fn()) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return {
    onClose,
    ...render(
      <QueryClientProvider client={queryClient}>
        <TransactionSourceModal transactionId={transactionId} onClose={onClose} />
      </QueryClientProvider>
    ),
  };
}

beforeEach(() => {
  jest.clearAllMocks();
});

describe('TransactionSourceModal (Track C/C7)', () => {
  it('renders nothing when no transaction is being viewed', () => {
    const { toJSON } = renderModal(null);
    expect(toJSON()).toBeNull();
    expect(transactions.source).not.toHaveBeenCalled();
  });

  it('shows the imported row, file, account, period, and import date when available', async () => {
    transactions.source.mockResolvedValue(source());

    renderModal('t-1');

    expect(await screen.findByText('march-statement.pdf')).toBeTruthy();
    expect(screen.getByText('HDFC Savings')).toBeTruthy();
    expect(screen.getByText('Row 14')).toBeTruthy();
    expect(transactions.source).toHaveBeenCalledWith('t-1');
  });

  it('states plainly that a manual entry has no statement row, rather than an error', async () => {
    transactions.source.mockResolvedValue(source({
      available: false, sourceLabel: 'MANUAL', statementImportId: null, fileName: null,
      rowPosition: null, importedAt: null, accountName: null,
      statementPeriodStart: null, statementPeriodEnd: null,
    }));

    renderModal('t-1');

    expect(await screen.findByText('You entered this transaction yourself.')).toBeTruthy();
    expect(screen.queryByText('Row')).toBeNull();
  });

  it('gives Gmail-imported transactions their own explanation, not the manual one', async () => {
    transactions.source.mockResolvedValue(source({
      available: false, sourceLabel: 'GMAIL_IMPORT', statementImportId: null, fileName: null,
      rowPosition: null, importedAt: null, accountName: null,
      statementPeriodStart: null, statementPeriodEnd: null,
    }));

    renderModal('t-1');

    expect(await screen.findByText('Imported from a Gmail receipt, not a bank statement row.')).toBeTruthy();
  });

  it('says so, rather than nothing, when the request fails', async () => {
    transactions.source.mockRejectedValue(new Error('network down'));

    renderModal('t-1');

    expect(await screen.findByText("Couldn't load where this came from.")).toBeTruthy();
  });

  it('calls onClose when Close is pressed', async () => {
    transactions.source.mockResolvedValue(source());
    const { onClose } = renderModal('t-1');

    fireEvent.press(await screen.findByText('Close'));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it('omits the account field when the source statement was deleted and had no resolvable account', async () => {
    transactions.source.mockResolvedValue(source({ accountName: null }));

    renderModal('t-1');

    expect(await screen.findByText('march-statement.pdf')).toBeTruthy();
    expect(screen.queryByText('Account')).toBeNull();
  });
});
