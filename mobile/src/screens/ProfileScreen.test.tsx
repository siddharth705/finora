import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ProfileScreen } from './ProfileScreen';
import { userApi, type UserSettings } from '../api/endpoints';

jest.mock('../api/endpoints', () => ({
  userApi: { get: jest.fn(), update: jest.fn() },
}));

const user = userApi as jest.Mocked<typeof userApi>;

// Invented, matching the value the rest of this suite uses. Declared once so the hygiene marker
// sits in one place rather than on every line that mentions it.
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
  passwordChangedAt: '2026-07-06T00:00:00Z',
};

const navigation = { navigate: jest.fn() };

function renderScreen() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      {/* Only `navigation.navigate` is used; the rest of the prop is irrelevant here. */}
      <ProfileScreen navigation={navigation as never} route={{ key: 'p', name: 'Profile' } as never} />
    </QueryClientProvider>
  );
}

async function settle() {
  await act(async () => {});
}

describe('ProfileScreen', () => {
  beforeEach(() => {
    navigation.navigate.mockReset();
    user.get.mockReset().mockResolvedValue(settings);
    user.update.mockReset().mockImplementation(async (body) => ({ ...settings, ...body }) as UserSettings);
  });

  it('shows the identity it loaded', async () => {
    renderScreen();

    expect(await screen.findByText('Ada Lovelace')).toBeTruthy();
    // Twice by design: the identity header and the read-only Email field below it.
    expect(screen.getAllByText('you@example.com')).toHaveLength(2);
    expect(screen.getByText(/Member since January 2026/)).toBeTruthy();
  });

  it('saves an edited name', async () => {
    renderScreen();
    await screen.findByText('Ada Lovelace');

    fireEvent.changeText(screen.getByLabelText('Full name'), 'Ada King');
    fireEvent.press(screen.getByRole('button', { name: /Save changes/ }));
    await settle();

    await waitFor(() => expect(user.update).toHaveBeenCalledWith({ fullName: 'Ada King' }));
  });

  it('keeps Save disabled until the name actually changes', async () => {
    renderScreen();
    await screen.findByText('Ada Lovelace');

    expect(screen.getByRole('button', { name: /Save changes/ }).props.accessibilityState.disabled).toBe(true);

    fireEvent.changeText(screen.getByLabelText('Full name'), 'Ada King');
    await settle();

    expect(screen.getByRole('button', { name: /Save changes/ }).props.accessibilityState.disabled).toBe(false);
  });

  // Same rule the web Register form enforces -- two clients disagreeing about what a name may
  // contain is a support problem, even though the backend validates independently.
  it('refuses a name with digits or symbols', async () => {
    renderScreen();
    await screen.findByText('Ada Lovelace');

    fireEvent.changeText(screen.getByLabelText('Full name'), 'Ada99');
    fireEvent.press(screen.getByRole('button', { name: /Save changes/ }));
    await settle();

    expect(user.update).not.toHaveBeenCalled();
  });

  it('masks the phone number rather than printing it in full', async () => {
    renderScreen();
    await screen.findByText('Ada Lovelace');

    // Twice by design: Personal Information and the Security Overview summary. The point is that
    // the raw number appears nowhere.
    expect(screen.getAllByText(MASKED_PHONE)).toHaveLength(2);
    expect(screen.queryByText(PHONE)).toBeNull();
  });

  it('reports when the password was last changed', async () => {
    renderScreen();

    expect(await screen.findByText(/Last changed .* ago/)).toBeTruthy();
  });

  // Security here is a read-only summary; the actions live in Settings.
  it('sends the user to Settings to manage security', async () => {
    renderScreen();
    await screen.findByText('Ada Lovelace');

    fireEvent.press(screen.getByLabelText('Manage security in Settings'));
    await settle();

    expect(navigation.navigate).toHaveBeenCalledWith('Settings');
  });

  it('says so plainly when the profile could not be loaded', async () => {
    user.get.mockReset().mockRejectedValue(new Error('boom'));
    renderScreen();

    expect(await screen.findByText(/Couldn't load your profile/)).toBeTruthy();
  });
});
