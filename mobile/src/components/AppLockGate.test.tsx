import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { Text } from 'react-native';
import * as SecureStore from 'expo-secure-store';
import * as LocalAuthentication from 'expo-local-authentication';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AppLockGate } from './AppLockGate';
import { AuthProvider } from '../context/AuthContext';
import { ThemeProvider } from '../theme';
import App from '../../App';

// Same reasoning as OfflineBanner.test.tsx / RootWarningBanner.test.tsx: isolates the mount test
// to App's own composition rather than the whole navigation tree.
jest.mock('../navigation/RootNavigator', () => {
  const { Text: RNText } = require('react-native');
  return { RootNavigator: () => <RNText>navigator</RNText> };
});

const mockedAuthenticateAsync = LocalAuthentication.authenticateAsync as jest.MockedFunction<
  typeof LocalAuthentication.authenticateAsync
>;

const LOCK_TEXT = /Finora is locked/i;

/** Seeds a signed-in session the same way AuthContext.test.tsx does -- AuthProvider reads these
 *  SecureStore keys on its bootstrap effect, which AppLockGate waits on before it can lock. */
async function signIn() {
  await SecureStore.setItemAsync('finora_token', 'access-token');
  await SecureStore.setItemAsync('finora_email', 'someone@example.com');
}

async function enableAppLock() {
  await SecureStore.setItemAsync('finora_app_lock_enabled', 'true');
}

function renderGate(children = <Text>protected content</Text>) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <AuthProvider>
          <AppLockGate>{children}</AppLockGate>
        </AuthProvider>
      </ThemeProvider>
    </QueryClientProvider>
  );
}

describe('AppLockGate', () => {
  it('renders children unlocked while there is no session, regardless of the setting', async () => {
    await enableAppLock();
    renderGate();

    expect(await screen.findByText('protected content')).toBeTruthy();
    expect(screen.queryByText(LOCK_TEXT)).toBeNull();
  });

  it('renders children unlocked for a session with the setting off', async () => {
    await signIn();
    renderGate();

    expect(await screen.findByText('protected content')).toBeTruthy();
    expect(screen.queryByText(LOCK_TEXT)).toBeNull();
  });

  it('locks and auto-prompts a session with the setting on, unlocking on success', async () => {
    await signIn();
    await enableAppLock();
    mockedAuthenticateAsync.mockResolvedValueOnce({ success: true });
    renderGate();

    // The auto-prompt fires as soon as the setting loads -- a real success clears it without
    // the user ever needing to press the Unlock button. Waiting on the mock call first (rather
    // than going straight for "lock text is gone", which is trivially true before the effect has
    // even run) is what actually proves the auto-prompt fired.
    await waitFor(() => expect(mockedAuthenticateAsync).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(screen.queryByText(LOCK_TEXT)).toBeNull());
    expect(screen.getByText('protected content')).toBeTruthy();
  });

  it('stays locked and offers a retry when the auto-prompt fails', async () => {
    await signIn();
    await enableAppLock();
    mockedAuthenticateAsync.mockResolvedValueOnce({ success: false, error: 'authentication_failed' });
    renderGate();

    await waitFor(() => expect(screen.getByText(LOCK_TEXT)).toBeTruthy());
    expect(screen.queryByText('protected content')).toBeNull();

    mockedAuthenticateAsync.mockResolvedValueOnce({ success: true });
    await act(async () => fireEvent.press(screen.getByText('Unlock')));

    await waitFor(() => expect(screen.queryByText(LOCK_TEXT)).toBeNull());
    expect(screen.getByText('protected content')).toBeTruthy();
  });

  it('offers Sign Out as an escape hatch from the lock screen', async () => {
    await signIn();
    await enableAppLock();
    mockedAuthenticateAsync.mockResolvedValueOnce({ success: false, error: 'authentication_failed' });
    renderGate();

    await waitFor(() => expect(screen.getByText(LOCK_TEXT)).toBeTruthy());
    expect(screen.getByText('Sign Out')).toBeTruthy();
  });
});

describe('the app actually mounts it', () => {
  it('locks the real App tree for a signed-in session with the setting on', async () => {
    await signIn();
    await enableAppLock();
    mockedAuthenticateAsync.mockResolvedValueOnce({ success: false, error: 'authentication_failed' });
    render(<App />);

    await waitFor(() => expect(screen.getByText(LOCK_TEXT)).toBeTruthy());
    // Locked means the navigator underneath is not what's on screen -- same wiring-not-behavior
    // assertion OfflineBanner.test.tsx and RootWarningBanner.test.tsx make for their own boundary.
    expect(screen.queryByText('navigator')).toBeNull();
  });
});
