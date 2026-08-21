import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { AppState, Text, type AppStateStatus } from 'react-native';
import * as SecureStore from 'expo-secure-store';
import * as LocalAuthentication from 'expo-local-authentication';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import * as appLock from '../lib/appLock';
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

beforeEach(() => {
  appStateListener = undefined;
  AppState.currentState = 'active';
  jest.spyOn(AppState, 'addEventListener').mockImplementation((event, listener) => {
    if (event === 'change') appStateListener = listener as (status: AppStateStatus) => void;
    return { remove: jest.fn() };
  });
});

afterEach(() => {
  jest.restoreAllMocks();
});

/** The real native AppState module never fires a 'change' event under the test runner (there's
 *  no bridge), so it's a safe no-op for every other test here -- but the two foreground tests
 *  below need to actually trigger the listener AppLockGate registers, so they capture it directly
 *  rather than trying to simulate a real native event.
 *
 *  Driving it through the SAME listener callback both times (not just mutating
 *  AppState.currentState directly) matters: the component's own "was I in the background" memory
 *  is a ref written INSIDE that callback (`appState.current = next`), not a read of the module's
 *  live value -- so a background transition has to go through the listener too, or the later
 *  foreground call has nothing to compare against and never detects a transition at all. */
let appStateListener: ((status: AppStateStatus) => void) | undefined;

function goToBackground() {
  appStateListener?.('background');
}

function returnToForeground() {
  appStateListener?.('active');
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

  // Regression coverage: AppLockGate must not cache the setting in state across the session --
  // AppLockSection (Settings) writes straight to SecureStore with no shared state or event bridge
  // back to this component, so a cached value read once at mount would go stale the moment the
  // user flips the toggle without restarting the app.
  //
  // Both tests spy on appLock.isEnabled() directly rather than writing to SecureStore and racing
  // it against the component's own read -- this component reads the setting TWICE (once from the
  // mount effect, once from the foreground listener), and an unsynchronized SecureStore write from
  // the test can land before either read, making a test pass for the wrong reason (the mount
  // effect happening to see the new value, rather than the foreground listener actually re-reading
  // it -- this is exactly what happened on the first version of these two tests, caught only by
  // deliberately reverting the fix locally and finding they still passed against the bug).
  // Queuing exact resolved values per call removes the race and pins down which read is which.
  describe('the setting can change mid-session, without an app restart', () => {
    it('engages on the very next foreground after being turned ON mid-session', async () => {
      await signIn();
      const isEnabledSpy = jest.spyOn(appLock, 'isEnabled').mockResolvedValueOnce(false);
      renderGate();
      expect(await screen.findByText('protected content')).toBeTruthy();
      await waitFor(() => expect(isEnabledSpy).toHaveBeenCalledTimes(1));

      // Simulates the toggle having been turned on in Settings since the mount check above.
      isEnabledSpy.mockResolvedValueOnce(true);
      goToBackground();
      mockedAuthenticateAsync.mockResolvedValueOnce({ success: false, error: 'authentication_failed' });
      await act(async () => returnToForeground());

      await waitFor(() => expect(screen.getByText(LOCK_TEXT)).toBeTruthy());
    });

    it('does not lock on the next foreground after being turned OFF mid-session', async () => {
      await signIn();
      const isEnabledSpy = jest.spyOn(appLock, 'isEnabled').mockResolvedValueOnce(true);
      mockedAuthenticateAsync.mockResolvedValueOnce({ success: true });
      renderGate();
      // Let the mount-time auto-prompt actually happen and resolve before moving on -- waiting on
      // the mock call first (rather than going straight for "lock text is gone", which is
      // trivially true before the effect has even run) is what proves it really unlocked rather
      // than never having locked in the first place.
      await waitFor(() => expect(mockedAuthenticateAsync).toHaveBeenCalledTimes(1));
      await waitFor(() => expect(screen.queryByText(LOCK_TEXT)).toBeNull());

      // Simulates the toggle having been turned off in Settings since the mount check above.
      isEnabledSpy.mockResolvedValueOnce(false);
      goToBackground();
      await act(async () => returnToForeground());

      // Give the foreground check a chance to actually run before asserting no re-lock happened.
      await waitFor(() => expect(isEnabledSpy).toHaveBeenCalledTimes(2));
      expect(mockedAuthenticateAsync).toHaveBeenCalledTimes(1);
      expect(screen.queryByText(LOCK_TEXT)).toBeNull();
      expect(screen.getByText('protected content')).toBeTruthy();
    });
  });
});

// Regression coverage for a real on-device bug: presenting the native Face ID sheet drives
// AppState through inactive/background and back to active on its own, as a side effect of the
// prompt itself -- not the user backgrounding the app. Before authenticatingRef existed, the
// foreground listener could not tell that transition apart from a genuine return, so it re-locked
// and re-prompted, which blipped AppState again, on and on: Face ID asked again and again in a
// loop, exactly as reported.
describe('a self-induced AppState blip during authentication', () => {
  it('does not re-prompt when the prompt itself causes a background/foreground blip mid-flight', async () => {
    await signIn();
    await enableAppLock();
    let resolveAuth: ((result: LocalAuthentication.LocalAuthenticationResult) => void) | undefined;
    mockedAuthenticateAsync.mockImplementationOnce(
      () => new Promise((resolve) => { resolveAuth = resolve; })
    );
    renderGate();

    // The mount-time auto-prompt is now in flight, unresolved -- this is the window a real Face
    // ID sheet occupies on-device.
    await waitFor(() => expect(mockedAuthenticateAsync).toHaveBeenCalledTimes(1));

    // The sheet itself drives this same blip -- same listener, same event shapes the real
    // AppLockGate.tsx doc comment describes, not a distinct user action.
    await act(async () => {
      goToBackground();
      returnToForeground();
    });

    // The bug: this would have been 2 (or climbing forever on a real device, one more prompt per
    // blip). A second concurrent authenticateAsync() call while the first is still unresolved is
    // exactly the loop that was reported.
    expect(mockedAuthenticateAsync).toHaveBeenCalledTimes(1);
    expect(screen.getByText(LOCK_TEXT)).toBeTruthy();

    // The original prompt still resolves normally afterward -- the guard only suppresses the
    // self-induced blip, not real authentication.
    await act(async () => resolveAuth?.({ success: true }));
    await waitFor(() => expect(screen.queryByText(LOCK_TEXT)).toBeNull());
    expect(screen.getByText('protected content')).toBeTruthy();
  });

  it('still re-locks on a genuine foreground return once authentication has finished', async () => {
    await signIn();
    const isEnabledSpy = jest.spyOn(appLock, 'isEnabled').mockResolvedValueOnce(true);
    mockedAuthenticateAsync.mockResolvedValueOnce({ success: true });
    renderGate();
    await waitFor(() => expect(mockedAuthenticateAsync).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(screen.queryByText(LOCK_TEXT)).toBeNull());

    // A real return, well after the earlier prompt already settled -- authenticatingRef is back
    // to false by now, so this must not be swallowed the way the self-induced blip above was.
    isEnabledSpy.mockResolvedValueOnce(true);
    mockedAuthenticateAsync.mockResolvedValueOnce({ success: false, error: 'authentication_failed' });
    goToBackground();
    await act(async () => returnToForeground());

    await waitFor(() => expect(mockedAuthenticateAsync).toHaveBeenCalledTimes(2));
    expect(screen.getByText(LOCK_TEXT)).toBeTruthy();
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
