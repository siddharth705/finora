import { fireEvent, render, screen } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { SupportTicketDetailScreen } from './SupportTicketDetailScreen';
import { supportApi, type SupportTicketDetail } from '../api/endpoints';
import type { MoreStackParamList } from '../navigation/types';

jest.mock('../api/endpoints', () => ({
  supportApi: { list: jest.fn(), create: jest.fn(), detail: jest.fn(), downloadAttachment: jest.fn() },
}));

const api = supportApi as jest.Mocked<typeof supportApi>;

type Props = NativeStackScreenProps<MoreStackParamList, 'SupportTicketDetail'>;

function renderScreen(ticketId = 'ticket-1') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  const navigation = {} as unknown as Props['navigation'];
  const route = { key: 'SupportTicketDetail', name: 'SupportTicketDetail', params: { ticketId } } as Props['route'];
  return render(
    <QueryClientProvider client={queryClient}>
      <SupportTicketDetailScreen navigation={navigation} route={route} />
    </QueryClientProvider>
  );
}

function ticket(overrides: Partial<SupportTicketDetail> = {}): SupportTicketDetail {
  return {
    id: 'ticket-1', ticketNumber: 'SUP-000001', userId: 'user-1', category: 'TECHNICAL_ISSUE',
    subject: 'Import stuck', status: 'OPEN', claimedByAdminId: null,
    createdAt: '2026-09-04T10:00:00Z', updatedAt: '2026-09-04T10:00:00Z',
    description: 'Progress bar froze at 60%.', source: 'MOBILE_ANDROID', appVersion: '1.0.0',
    resolvedAt: null, closedAt: null, attachments: [],
    ...overrides,
  };
}

describe('SupportTicketDetailScreen', () => {
  beforeEach(() => {
    api.detail.mockReset();
    api.downloadAttachment.mockReset();
  });

  it('renders the ticket subject, description and status', async () => {
    api.detail.mockResolvedValue(ticket());
    renderScreen();

    expect(await screen.findByText('Import stuck')).toBeTruthy();
    expect(screen.getByText('Progress bar froze at 60%.')).toBeTruthy();
    expect(screen.getByText('Open')).toBeTruthy();
    expect(screen.getByText('SUP-000001 · Technical issue')).toBeTruthy();
  });

  it("shows a not-found message when the ticket 404s (not the caller's, or does not exist)", async () => {
    api.detail.mockRejectedValue({ isAxiosError: true, response: { status: 404, data: { message: "This ticket doesn't exist, or isn't yours to view." } } });
    renderScreen();

    expect(await screen.findByText('Ticket not found')).toBeTruthy();
  });

  it('lists an attachment and shares it when pressed', async () => {
    api.detail.mockResolvedValue(ticket({
      attachments: [{ id: 'att-1', filename: 'screenshot.png', contentType: 'image/png', sizeBytes: 2048 }],
    }));
    api.downloadAttachment.mockResolvedValue(undefined);
    renderScreen();

    const row = await screen.findByLabelText(/screenshot\.png, 2\.0 KB\. Share/);
    fireEvent.press(row);

    expect(api.downloadAttachment).toHaveBeenCalledWith('ticket-1', 'att-1', 'screenshot.png', 'image/png');
  });

  it("tells the user a resolved ticket can't be reopened", async () => {
    api.detail.mockResolvedValue(ticket({ status: 'RESOLVED' }));
    renderScreen();

    expect(await screen.findByText(/can't be reopened/)).toBeTruthy();
  });
});
