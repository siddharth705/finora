import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { VerifyEmailChangeScreen } from './VerifyEmailChangeScreen';
import { emailChangeApi } from '../../api/endpoints';
import type { MoreStackParamList } from '../../navigation/types';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';

jest.mock('../../api/endpoints', () => ({
  emailChangeApi: { verify: jest.fn(), complete: jest.fn() },
}));

const api = emailChangeApi as jest.Mocked<typeof emailChangeApi>;

type Props = NativeStackScreenProps<MoreStackParamList, 'VerifyEmailChange'>;

const mockNavigate = jest.fn();

function renderScreen(params: { sessionId?: string; token?: string } | undefined) {
  const navigation = { navigate: mockNavigate } as unknown as Props['navigation'];
  const route = { key: 'VerifyEmailChange', name: 'VerifyEmailChange', params } as Props['route'];
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <VerifyEmailChangeScreen navigation={navigation} route={route} />
    </QueryClientProvider>
  );
}

describe('VerifyEmailChangeScreen', () => {
  beforeEach(() => {
    mockNavigate.mockReset();
    api.verify.mockReset();
    api.complete.mockReset();
  });

  it('shows a loading state, then chains verify() into complete(), showing the new email on success', async () => {
    api.verify.mockResolvedValue({ message: 'Verified.' });
    api.complete.mockResolvedValue({ message: 'Your email address has been updated.', email: 'jane.new@example.com' });

    renderScreen({ sessionId: 'session-1', token: 'real-token' });

    expect(screen.getByText('Confirming your new email…')).toBeTruthy();

    await waitFor(() => expect(screen.getByText('Email updated')).toBeTruthy());
    expect(api.verify).toHaveBeenCalledWith('session-1', 'real-token');
    expect(api.complete).toHaveBeenCalledWith('session-1');
    expect(screen.getByText(/jane\.new@example\.com/)).toBeTruthy();
  });

  it('falls back to complete() when verify() fails because the session was already verified/completed on an earlier visit, and shows success', async () => {
    api.verify.mockRejectedValue({
      isAxiosError: true,
      response: { data: { message: 'This step has already been completed, or the session is no longer valid.' } },
    });
    api.complete.mockResolvedValue({ message: 'Your email address has been updated.', email: 'jane.new@example.com' });

    renderScreen({ sessionId: 'session-1', token: 'already-used-token' });

    await waitFor(() => expect(screen.getByText('Email updated')).toBeTruthy());
    expect(api.complete).toHaveBeenCalledWith('session-1');
  });

  it('shows verify()\'s own error message when both verify() and the complete() fallback fail (a genuinely wrong/expired token)', async () => {
    api.verify.mockRejectedValue({
      isAxiosError: true,
      response: { data: { message: 'This verification link is invalid.' } },
    });
    api.complete.mockRejectedValue({
      isAxiosError: true,
      response: { data: { message: 'Confirm the link sent to your new email before completing this change.' } },
    });

    renderScreen({ sessionId: 'session-1', token: 'wrong-token' });

    await waitFor(() => expect(screen.getByText('Confirmation failed')).toBeTruthy());
    expect(screen.getByText('This verification link is invalid.')).toBeTruthy();
  });

  it('shows an error immediately, with no API calls, when the deep link is missing sessionId or token', () => {
    renderScreen({ sessionId: 'session-1', token: undefined });

    expect(screen.getByText('Confirmation failed')).toBeTruthy();
    expect(screen.getByText(/missing information/)).toBeTruthy();
    expect(api.verify).not.toHaveBeenCalled();
  });

  it('navigates back to Settings from the success state', async () => {
    api.verify.mockResolvedValue({ message: 'Verified.' });
    api.complete.mockResolvedValue({ message: 'Updated.', email: 'jane.new@example.com' });
    renderScreen({ sessionId: 'session-1', token: 'real-token' });
    await waitFor(() => expect(screen.getByText('Email updated')).toBeTruthy());

    fireEvent.press(screen.getByRole('button', { name: 'Back to Settings' }));

    expect(mockNavigate).toHaveBeenCalledWith('Settings');
  });
});
