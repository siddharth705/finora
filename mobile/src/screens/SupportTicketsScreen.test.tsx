import { fireEvent, render, screen } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SupportTicketsScreen } from './SupportTicketsScreen';
import { supportApi, type SupportTicketSummary } from '../api/endpoints';

const mockNavigate = jest.fn();
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ navigate: mockNavigate }),
}));

jest.mock('./support/NewTicketSheet', () => ({
  // A stand-in, not the real sheet -- NewTicketSheet.test.tsx already covers its own form. This
  // just proves SupportTicketsScreen opens/wires it, matching NewTicketModal.test.tsx's own split
  // of responsibility on web.
  NewTicketSheet: ({ onCreated }: { onCreated: (t: { id: string }) => void }) => {
    const { Pressable, Text } = require('react-native');
    return (
      <Pressable accessibilityRole="button" onPress={() => onCreated({ id: 'new-ticket-1' })}>
        <Text>Fake New Ticket Sheet</Text>
      </Pressable>
    );
  },
}));

jest.mock('../api/endpoints', () => ({
  supportApi: { list: jest.fn(), create: jest.fn(), detail: jest.fn(), downloadAttachment: jest.fn() },
}));

const api = supportApi as jest.Mocked<typeof supportApi>;

function renderScreen() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <SupportTicketsScreen />
    </QueryClientProvider>
  );
}

function summary(overrides: Partial<SupportTicketSummary> = {}): SupportTicketSummary {
  return {
    id: 'ticket-1', ticketNumber: 'SUP-000001', userId: 'user-1', category: 'STATEMENT_IMPORT',
    subject: 'Import stuck', status: 'OPEN', claimedByAdminId: null,
    createdAt: '2026-09-04T10:00:00Z', updatedAt: '2026-09-04T10:00:00Z',
    ...overrides,
  };
}

describe('SupportTicketsScreen', () => {
  beforeEach(() => {
    mockNavigate.mockReset();
    api.list.mockReset();
  });

  it('shows the empty state when the user has filed no tickets', async () => {
    api.list.mockResolvedValue({ content: [], page: 0, size: 25, totalElements: 0, totalPages: 0 });
    renderScreen();

    expect(await screen.findByText('No support tickets yet')).toBeTruthy();
  });

  it('lists a ticket with its number, subject and status', async () => {
    api.list.mockResolvedValue({ content: [summary({ status: 'IN_PROGRESS' })], page: 0, size: 25, totalElements: 1, totalPages: 1 });
    renderScreen();

    expect(await screen.findByText('Import stuck')).toBeTruthy();
    expect(screen.getByText('SUP-000001')).toBeTruthy();
    expect(screen.getByText('In Progress')).toBeTruthy();
  });

  it('navigates to the ticket detail route when a row is pressed', async () => {
    api.list.mockResolvedValue({ content: [summary()], page: 0, size: 25, totalElements: 1, totalPages: 1 });
    renderScreen();

    fireEvent.press(await screen.findByText('Import stuck'));

    expect(mockNavigate).toHaveBeenCalledWith('SupportTicketDetail', { ticketId: 'ticket-1' });
  });

  it('shows a distinct error state (not the empty state) when the list fails to load', async () => {
    api.list.mockRejectedValue(new Error('network down'));
    renderScreen();

    expect(await screen.findByText("Couldn't load your tickets.")).toBeTruthy();
    expect(screen.queryByText('No support tickets yet')).toBeNull();
  });

  it('opens New Ticket and navigates to the created ticket', async () => {
    api.list.mockResolvedValue({ content: [], page: 0, size: 25, totalElements: 0, totalPages: 0 });
    renderScreen();
    await screen.findByText('No support tickets yet');

    fireEvent.press(screen.getByLabelText('New ticket'));
    fireEvent.press(screen.getByText('Fake New Ticket Sheet'));

    expect(mockNavigate).toHaveBeenCalledWith('SupportTicketDetail', { ticketId: 'new-ticket-1' });
  });
});
