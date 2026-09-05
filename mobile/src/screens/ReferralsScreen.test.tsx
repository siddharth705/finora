import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { Linking, Share } from 'react-native';
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

const canOpenURL = jest.spyOn(Linking, 'canOpenURL');
const openURL = jest.spyOn(Linking, 'openURL').mockResolvedValue(undefined);
const shareSpy = jest.spyOn(Share, 'share').mockResolvedValue({ action: Share.sharedAction });

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
    canOpenURL.mockReset();
    openURL.mockClear();
    shareSpy.mockClear();
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

  it('opens WhatsApp with the code pre-filled when it is installed', async () => {
    api.mine.mockResolvedValue({ code: 'ABCD1234', referralCount: 0 });
    canOpenURL.mockResolvedValue(true);
    renderScreen();
    await screen.findByText('ABCD1234');

    fireEvent.press(screen.getByLabelText('Share via WhatsApp'));
    await settle();

    expect(openURL).toHaveBeenCalledWith(expect.stringContaining('whatsapp://send?text='));
    expect(openURL.mock.calls[0][0]).toContain(encodeURIComponent('ABCD1234'));
    expect(shareSpy).not.toHaveBeenCalled();
  });

  it('falls back to the OS share sheet when WhatsApp is not installed', async () => {
    api.mine.mockResolvedValue({ code: 'ABCD1234', referralCount: 0 });
    canOpenURL.mockResolvedValue(false);
    renderScreen();
    await screen.findByText('ABCD1234');

    fireEvent.press(screen.getByLabelText('Share via WhatsApp'));
    await settle();

    expect(openURL).not.toHaveBeenCalled();
    expect(shareSpy).toHaveBeenCalledWith(expect.objectContaining({ message: expect.stringContaining('ABCD1234') }));
  });

  it('opens the SMS/mail composers directly, with no installed-app check', async () => {
    api.mine.mockResolvedValue({ code: 'ABCD1234', referralCount: 0 });
    renderScreen();
    await screen.findByText('ABCD1234');

    fireEvent.press(screen.getByLabelText('Share via Messages'));
    await settle();
    fireEvent.press(screen.getByLabelText('Share via Email'));
    await settle();

    expect(canOpenURL).not.toHaveBeenCalled();
    expect(openURL).toHaveBeenCalledWith(expect.stringMatching(/^sms:/));
    expect(openURL).toHaveBeenCalledWith(expect.stringMatching(/^mailto:/));
  });

  it('opens the OS share sheet from "More"', async () => {
    api.mine.mockResolvedValue({ code: 'ABCD1234', referralCount: 0 });
    renderScreen();
    await screen.findByText('ABCD1234');

    fireEvent.press(screen.getByLabelText('More share options'));
    await settle();

    expect(shareSpy).toHaveBeenCalledWith(expect.objectContaining({ message: expect.stringContaining('ABCD1234') }));
  });
});
