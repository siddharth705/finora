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
    statementDeleted: false,
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

  // The bug this guards: a row that WAS tracked but whose statement import was later deleted
  // used to render the exact same "predates tracking" sentence as a row that never had a
  // tracked position at all -- a false claim for this case.
  it('tells apart "the statement was deleted" from "this predates tracking"', async () => {
    transactions.source.mockResolvedValue(source({
      available: false, sourceLabel: 'CSV_IMPORT', statementDeleted: true, statementImportId: null,
      fileName: null, rowPosition: null, importedAt: null, accountName: null,
      statementPeriodStart: null, statementPeriodEnd: null,
    }));

    renderModal('t-1');

    expect(await screen.findByText('The bank statement this was imported from is no longer available.')).toBeTruthy();
    expect(screen.queryByText('Imported before Finora tracked exactly which row a transaction came from.')).toBeNull();
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

  // The bug this whole block guards: joining an unguarded fmtDate(null) into a template literal
  // renders the literal text "null" -- a real, documented case, since statementPeriodStart/End
  // are two independently nullable columns.
  describe('statement period rendering (mixed null dates)', () => {
    it('shows only the start date when the end date is unknown, never the word "null"', async () => {
      transactions.source.mockResolvedValue(source({ statementPeriodStart: '2026-03-01', statementPeriodEnd: null }));

      renderModal('t-1');

      // fmtDate renders en-IN day-month-year, e.g. "1 Mar 2026" -- not the US "Mar 1, 2026" order.
      expect(await screen.findByText('1 Mar 2026')).toBeTruthy();
      expect(screen.queryByText(/null/)).toBeNull();
    });

    it('shows only the end date when the start date is unknown, never the word "null"', async () => {
      transactions.source.mockResolvedValue(source({ statementPeriodStart: null, statementPeriodEnd: '2026-03-31' }));

      renderModal('t-1');

      expect(await screen.findByText('31 Mar 2026')).toBeTruthy();
      expect(screen.queryByText(/null/)).toBeNull();
    });

    it('omits the statement period field entirely when neither date is known', async () => {
      transactions.source.mockResolvedValue(source({ statementPeriodStart: null, statementPeriodEnd: null }));

      renderModal('t-1');

      expect(await screen.findByText('march-statement.pdf')).toBeTruthy();
      expect(screen.queryByText('Statement period')).toBeNull();
    });

    it('joins both dates when both are known', async () => {
      transactions.source.mockResolvedValue(source());

      renderModal('t-1');

      expect(await screen.findByText('1 Mar 2026 – 31 Mar 2026')).toBeTruthy();
    });
  });
});
