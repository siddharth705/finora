import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import * as Clipboard from 'expo-clipboard';
import { ReferralsScreen } from './ReferralsScreen';
import { referralsApi } from '../api/endpoints';

jest.mock('../api/endpoints', () => ({
  referralsApi: { myCode: jest.fn(), mine: jest.fn() },
}));

jest.mock('expo-clipboard', () => ({
  setStringAsync: jest.fn().mockResolvedValue(true),
}));

const api = referralsApi as jest.Mocked<typeof referralsApi>;
const clipboard = Clipboard as jest.Mocked<typeof Clipboard>;

function renderScreen() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ReferralsScreen />
    </QueryClientProvider>
  );
}

async function settle() {
  await act(async () => {});
}

describe('ReferralsScreen', () => {
  beforeEach(() => {
    api.mine.mockReset();
    clipboard.setStringAsync.mockClear();
  });

  it('shows the code and a zero count for a user with no referrals yet', async () => {
    api.mine.mockResolvedValue({ code: 'ABCD1234', referralCount: 0 });
    renderScreen();

    expect(await screen.findByText('ABCD1234')).toBeTruthy();
    expect(screen.getByText('0')).toBeTruthy();
  });

  it('shows the real referral count once it loads', async () => {
    api.mine.mockResolvedValue({ code: 'ABCD1234', referralCount: 7 });
    renderScreen();

    expect(await screen.findByText('7')).toBeTruthy();
  });

  it('copies the code to the clipboard and shows a transient "Copied" confirmation', async () => {
    api.mine.mockResolvedValue({ code: 'ABCD1234', referralCount: 0 });
    renderScreen();
    await screen.findByText('ABCD1234');

    fireEvent.press(screen.getByLabelText('Copy referral code'));
    await settle();

    expect(clipboard.setStringAsync).toHaveBeenCalledWith('ABCD1234');
    expect(await screen.findByLabelText('Copied')).toBeTruthy();
  });

  it('shows an error state with a retry action when the code fails to load', async () => {
    api.mine.mockRejectedValue(new Error('network down'));
    renderScreen();

    expect(await screen.findByText("Couldn't load your referral code.")).toBeTruthy();
  });
});
