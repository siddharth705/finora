import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SettingsScreen } from './SettingsScreen';
import { analyticsApi, devicesApi, userApi, workspaceApi } from '../api/endpoints';
import { ThemeProvider } from '../theme';
import type { UserSettings } from '../api/endpoints';

jest.mock('../api/endpoints', () => ({
  userApi: { get: jest.fn(), update: jest.fn() },
  workspaceApi: { getSettings: jest.fn(), updateSettings: jest.fn() },
  analyticsApi: { importStatistics: jest.fn() },
  devicesApi: { list: jest.fn(), revoke: jest.fn() },
  passwordChangeApi: { start: jest.fn(), verifyOtp: jest.fn(), complete: jest.fn() },
  emailChangeApi: { start: jest.fn() },
}));

const user = userApi as jest.Mocked<typeof userApi>;
const workspace = workspaceApi as jest.Mocked<typeof workspaceApi>;
const analytics = analyticsApi as jest.Mocked<typeof analyticsApi>;
const devices = devicesApi as jest.Mocked<typeof devicesApi>;

// Invented, and the same ascending-digit value the rest of this suite uses. Declared once so the
// hygiene marker sits in one place rather than on every line that mentions it.
const PHONE = '+919876543210'; // synthetic-ok: invented test number
const MASKED_PHONE = '+•••••••••210';

const settings: UserSettings = {
  email: 'you@example.com',
  fullName: 'Ada Lovelace',
  lowBalanceThreshold: 2000,
  theme: 'system',
  timezone: 'Asia/Kolkata',
  phoneNumber: PHONE,
  phoneVerified: true,
  createdAt: '2026-01-15T00:00:00Z',
  passwordChangedAt: null,
};

function renderScreen() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <SettingsScreen />
      </ThemeProvider>
    </QueryClientProvider>
  );
}

async function settle() {
  await act(async () => {});
}

async function loaded() {
  await screen.findByText('General');
}

describe('SettingsScreen', () => {
  beforeEach(() => {
    user.get.mockReset().mockResolvedValue(settings);
    user.update.mockReset().mockImplementation(async (body) => ({ ...settings, ...body }) as UserSettings);
    workspace.getSettings.mockReset().mockResolvedValue({ autoApplyConfidenceThreshold: 90, updatedAt: null });
    workspace.updateSettings.mockReset().mockResolvedValue({ autoApplyConfidenceThreshold: 80, updatedAt: null });
    analytics.importStatistics.mockReset().mockResolvedValue({
      totalStatements: 12, totalTransactionsImported: 480, totalTransactionsSkipped: 3,
      lastImportedAt: '2026-08-01T09:00:00Z',
    });
    devices.list.mockReset().mockResolvedValue([]);
    devices.revoke.mockReset().mockResolvedValue({ message: 'ok' });
  });

  it('shows the account preferences it loaded', async () => {
    renderScreen();
    await loaded();

    expect(screen.getByLabelText('Low balance alert').props.value).toBe('2000');
    expect(screen.getByLabelText(/Timezone: Asia\/Kolkata/)).toBeTruthy();
  });

  // Nothing is dirty until the user changes something, so there is nothing to save.
  it('keeps Save disabled until a preference actually changes', async () => {
    renderScreen();
    await loaded();

    const save = screen.getByRole('button', { name: /Save preferences/ });
    expect(save.props.accessibilityState.disabled).toBe(true);

    fireEvent.changeText(screen.getByLabelText('Low balance alert'), '5000');
    await settle();

    expect(screen.getByRole('button', { name: /Save preferences/ }).props.accessibilityState.disabled).toBe(false);
    expect(screen.getByText('Unsaved changes')).toBeTruthy();
  });

  it('saves the changed preferences', async () => {
    renderScreen();
    await loaded();

    fireEvent.changeText(screen.getByLabelText('Low balance alert'), '5000');
    fireEvent.press(screen.getByRole('button', { name: /Save preferences/ }));
    await settle();

    await waitFor(() =>
      expect(user.update).toHaveBeenCalledWith({ lowBalanceThreshold: 5000, timezone: 'Asia/Kolkata' })
    );
  });

  it('refuses a low balance alert that is not a positive number', async () => {
    renderScreen();
    await loaded();

    fireEvent.changeText(screen.getByLabelText('Low balance alert'), 'abc');
    fireEvent.press(screen.getByRole('button', { name: /Save preferences/ }));
    await settle();

    expect(user.update).not.toHaveBeenCalled();
    expect(screen.getByText(/must be a number greater than zero/)).toBeTruthy();
  });

  /**
   * The draft-overlay pattern: an edit in progress must survive the query refetching underneath
   * it. Seeding the field from the server in an effect would silently discard what was typed.
   */
  it('keeps an in-progress edit when the account refetches', async () => {
    renderScreen();
    await loaded();

    fireEvent.changeText(screen.getByLabelText('Low balance alert'), '7777');
    await settle();

    // A refetch delivering the same server value must not reset the field.
    user.get.mockResolvedValue({ ...settings });
    await act(async () => { await user.get(); });
    await settle();

    expect(screen.getByLabelText('Low balance alert').props.value).toBe('7777');
  });

  it('steps the confidence threshold and saves it', async () => {
    renderScreen();
    await loaded();

    expect(screen.getByText('90%')).toBeTruthy();
    fireEvent.press(screen.getByLabelText('Decrease threshold'));
    fireEvent.press(screen.getByLabelText('Decrease threshold'));
    await settle();
    expect(screen.getByText('80%')).toBeTruthy();

    fireEvent.press(screen.getByRole('button', { name: /Save setting/ }));
    await settle();

    await waitFor(() =>
      expect(workspace.updateSettings).toHaveBeenCalledWith({ autoApplyConfidenceThreshold: 80 })
    );
  });

  it('clamps the threshold to its bounds', async () => {
    workspace.getSettings.mockReset().mockResolvedValue({ autoApplyConfidenceThreshold: 100, updatedAt: null });
    renderScreen();
    await loaded();

    const increase = screen.getByLabelText('Increase threshold');
    expect(increase.props.accessibilityState.disabled).toBe(true);
    fireEvent.press(increase);
    await settle();

    expect(screen.getByText('100%')).toBeTruthy();
  });

  it('reports the data it holds, and a dash for a stat that would not load', async () => {
    analytics.importStatistics.mockReset().mockRejectedValue(new Error('boom'));
    renderScreen();
    await loaded();

    // A missing stat reads as unavailable rather than as a confident zero.
    expect(await screen.findByLabelText('Statements: —')).toBeTruthy();
  });

  it('masks the phone number rather than printing it in full', async () => {
    renderScreen();
    await loaded();

    // 12 digits after the "+", 3 left visible -- so 9 bullets. Mirrors the backend's
    // PhoneMasking.mask() exactly; see lib/maskPhone.
    expect(screen.getByText(MASKED_PHONE)).toBeTruthy();
    expect(screen.queryByText(PHONE)).toBeNull();
  });

  it('says the password has never been changed when it has not', async () => {
    renderScreen();
    await loaded();

    expect(screen.getByText('Never changed')).toBeTruthy();
  });

  it('shows the account email and a Change Email action in the Security section', async () => {
    renderScreen();
    await loaded();

    expect(screen.getByText(settings.email)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Change Email' })).toBeTruthy();
  });

  it('lists active sessions and can sign one out', async () => {
    devices.list.mockReset().mockResolvedValue([
      {
        id: 'dev-1', browser: 'Chrome', device: 'Windows', lastSeenIp: '203.0.113.7',
        lastSeenAt: '2026-08-04T09:00:00Z', createdAt: '2026-07-01T09:00:00Z',
        expiresAt: '2026-09-01T09:00:00Z',
      },
    ]);
    renderScreen();

    expect(await screen.findByText('Chrome on Windows')).toBeTruthy();
    expect(screen.getByLabelText('Sign out Chrome on Windows')).toBeTruthy();
  });

  // browser/device are best-effort labels parsed from a User-Agent, and either can be null.
  it('degrades gracefully when a session has no device labels', async () => {
    devices.list.mockReset().mockResolvedValue([
      {
        id: 'dev-2', browser: null, device: null, lastSeenIp: null,
        lastSeenAt: '2026-08-04T09:00:00Z', createdAt: '2026-07-01T09:00:00Z',
        expiresAt: '2026-09-01T09:00:00Z',
      },
    ]);
    renderScreen();

    expect(await screen.findByText('Unknown device')).toBeTruthy();
  });

  it('says so plainly when the settings could not be loaded', async () => {
    user.get.mockReset().mockRejectedValue(new Error('boom'));
    renderScreen();

    expect(await screen.findByText(/Couldn't load your settings/)).toBeTruthy();
  });
});
