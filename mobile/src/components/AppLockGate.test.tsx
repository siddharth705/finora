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

const LOCK_TEXT = /Fynora is locked/i;

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
  appStateListeners = [];
  AppState.currentState = 'active';
  jest.spyOn(AppState, 'addEventListener').mockImplementation((event, listener) => {
    if (event !== 'change') return { remove: jest.fn() };
    const entry = listener as (status: AppStateStatus) => void;
    appStateListeners.push(entry);
    // remove() has to actually deregister, not just be a no-op spy: both AppLockGate and
    // AuthProvider re-register their 'change' listener whenever their own effect dependencies
    // change (token, in both cases -- see each component's own effect), which happens more than
    // once during a single test's bootstrap sequence. A no-op remove() left every torn-down
    // closure in the array alongside its replacement, all sharing AppLockGate's single
    // `appState` ref (a useRef is stable across those re-registrations) -- so the FIRST stale
    // listener to see a background->active transition flipped the shared ref and starved every
    // listener registered after it, including the current, live one. Actually removing the entry
    // here is what keeps the array matching what's really still subscribed.
    return {
      remove: jest.fn(() => {
        appStateListeners = appStateListeners.filter((l) => l !== entry);
      }),
    };
  });
  // appLock's isAuthenticating/justFinishedAuthenticating are module-level, not component state --
  // see appLock.ts's own comment on why -- which makes them a cross-test hazard without this: a
  // prior test's real authenticate() call stamps the real wall-clock time, easily within a later
  // test's own reground-grace window.
  appLock.__resetAuthenticatingStateForTests();
});

afterEach(() => {
  jest.restoreAllMocks();
});

/** The real native AppState module never fires a 'change' event under the test runner (there's
 *  no bridge), so it's a safe no-op for every other test here -- but the two foreground tests
 *  below need to actually trigger the listeners AppLockGate and AuthProvider each register, so
 *  they capture every 'change' listener directly rather than trying to simulate a real native
 *  event.
 *
 *  An array, not a single captured callback: AuthProvider registers its own 'change' listener too
 *  (Task 14 -- re-registers the push token on foreground), and both this component tree's mount
 *  order and real AppState's own fan-out (every listener is notified, not just the last one
 *  registered) mean a single-slot capture would silently stop replaying events to AppLockGate's
 *  listener the moment a second consumer registered one.
 *
 *  Driving it through the SAME listener callbacks both times (not just mutating
 *  AppState.currentState directly) matters: AppLockGate's own "was I in the background" memory is
 *  a ref written INSIDE its callback (`appState.current = next`), not a read of the module's live
 *  value -- so a background transition has to go through the listener too, or the later foreground
 *  call has nothing to compare against and never detects a transition at all. */
let appStateListeners: ((status: AppStateStatus) => void)[] = [];

function goToBackground() {
  appStateListeners.forEach((listener) => listener('background'));
}

function returnToForeground() {
  appStateListeners.forEach((listener) => listener('active'));
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
      // Past the mount-time auto-prompt's own reground grace window by the time the foreground
      // check below fires -- without this, that foreground check is itself indistinguishable
      // from the prompt's own trailing blip and gets correctly suppressed, same as the dedicated
      // "blip arrives after authenticate() has already resolved" tests below expect. This test's
      // job is to prove the SETTING is re-read fresh on a genuine foreground return, which needs
      // that return to actually reach the check, not to also prove the grace period itself.
      const nowSpy = jest.spyOn(Date, 'now').mockReturnValue(3_000_000);
      mockedAuthenticateAsync.mockResolvedValueOnce({ success: true });
      renderGate();
      // Let the mount-time auto-prompt actually happen and resolve before moving on -- waiting on
      // the mock call first (rather than going straight for "lock text is gone", which is
      // trivially true before the effect has even run) is what proves it really unlocked rather
      // than never having locked in the first place.
      await waitFor(() => expect(mockedAuthenticateAsync).toHaveBeenCalledTimes(1));
      await waitFor(() => expect(screen.queryByText(LOCK_TEXT)).toBeNull());

      // Simulates the toggle having been turned off in Settings since the mount check above, on
      // a genuine return well after the earlier prompt's own grace window has elapsed.
      nowSpy.mockReturnValue(3_000_000 + 5000);
      isEnabledSpy.mockResolvedValueOnce(false);
      goToBackground();
      await act(async () => returnToForeground());

      // Give the foreground check a chance to actually run before asserting no re-lock happened.
      await waitFor(() => expect(isEnabledSpy).toHaveBeenCalledTimes(2));
      expect(mockedAuthenticateAsync).toHaveBeenCalledTimes(1);
      expect(screen.queryByText(LOCK_TEXT)).toBeNull();
      expect(screen.getByText('protected content')).toBeTruthy();
      nowSpy.mockRestore();
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

  // The bug as actually reported on-device: the first version of this fix (authenticatingRef
  // alone, covering only the call while it was still in flight) did NOT stop the loop -- Face ID
  // kept re-prompting. Root cause: the native "app became active" notification for a dismissed
  // Face ID sheet can arrive AFTER authenticateAsync()'s own promise has already resolved, i.e.
  // once authenticatingRef is already back to false. Reproduces that exact ordering by mocking
  // Date.now() directly (not jest.useFakeTimers(), which also fakes the timers waitFor's own
  // polling relies on, and would make these tests hang) rather than by racing real elapsed time,
  // which a slow CI run could make flaky in either direction.
  describe('the blip arrives after authenticate() has already resolved', () => {
    afterEach(() => jest.restoreAllMocks());

    it('does not re-prompt when the blip lands within the reground grace period', async () => {
      await signIn();
      await enableAppLock();
      const nowSpy = jest.spyOn(Date, 'now').mockReturnValue(1_000_000);
      mockedAuthenticateAsync.mockResolvedValueOnce({ success: true });
      renderGate();

      await waitFor(() => expect(mockedAuthenticateAsync).toHaveBeenCalledTimes(1));
      await waitFor(() => expect(screen.queryByText(LOCK_TEXT)).toBeNull());
      // authResolvedAtRef is now stamped at 1_000_000 (authenticate() resolved just now).

      // 200ms later -- comfortably inside REGROUND_GRACE_MS -- the sheet's own dismissal notice
      // arrives as a background/foreground blip, same as it does on-device.
      nowSpy.mockReturnValue(1_000_000 + 200);
      await act(async () => {
        goToBackground();
        returnToForeground();
      });

      // The bug: this used to climb to 2, then 3, forever -- one more prompt per blip, none of
      // them ever landing outside the (nonexistent) grace window.
      expect(mockedAuthenticateAsync).toHaveBeenCalledTimes(1);
      expect(screen.queryByText(LOCK_TEXT)).toBeNull();
    });

    it('still re-locks on a genuine foreground return once the grace period has elapsed', async () => {
      await signIn();
      const isEnabledSpy = jest.spyOn(appLock, 'isEnabled').mockResolvedValueOnce(true);
      const nowSpy = jest.spyOn(Date, 'now').mockReturnValue(2_000_000);
      mockedAuthenticateAsync.mockResolvedValueOnce({ success: true });
      renderGate();
      await waitFor(() => expect(mockedAuthenticateAsync).toHaveBeenCalledTimes(1));
      await waitFor(() => expect(screen.queryByText(LOCK_TEXT)).toBeNull());

      // Well past REGROUND_GRACE_MS -- a real return, not the sheet's own trailing blip -- must
      // not be swallowed the way the two tests above are.
      nowSpy.mockReturnValue(2_000_000 + 5000);
      isEnabledSpy.mockResolvedValueOnce(true);
      mockedAuthenticateAsync.mockResolvedValueOnce({ success: false, error: 'authentication_failed' });
      goToBackground();
      await act(async () => returnToForeground());

      await waitFor(() => expect(mockedAuthenticateAsync).toHaveBeenCalledTimes(2));
      expect(screen.getByText(LOCK_TEXT)).toBeTruthy();
    });
  });
});

// Regression coverage for the actual reported root cause, found only after the two fixes above
// (both scoped entirely to AppLockGate's own local state) still did not stop the loop on-device:
// AppLockSection's "confirm to enable App Lock" prompt in Settings calls appLock.authenticate()
// completely independently of AppLockGate -- a second, uncoordinated caller neither prior fix had
// any visibility into. Its own AppState blip still reached AppLockGate's foreground listener as
// if it were a genuine return, and it locked the app on top of the confirmation the user had just
// given -- this is what actually starts the loop the moment someone turns the setting on, not
// something scoped to AppLockGate's own re-lock prompt.
describe('a prompt from elsewhere in the app (e.g. AppLockSection enabling the setting)', () => {
  afterEach(() => jest.restoreAllMocks());

  it('does not lock while that prompt is in flight, or shortly after it resolves', async () => {
    await signIn();
    // The setting starts OFF -- same as a user who has never turned this on before, about to for
    // the first time. AppLockGate's own mount check finds it off and does nothing.
    renderGate();
    expect(await screen.findByText('protected content')).toBeTruthy();

    const nowSpy = jest.spyOn(Date, 'now').mockReturnValue(4_000_000);
    // AppLockSection.handleToggle's own call, not routed through AppLockGate at all.
    let resolveExternalAuth: ((result: LocalAuthentication.LocalAuthenticationResult) => void) | undefined;
    mockedAuthenticateAsync.mockImplementationOnce(
      () => new Promise((resolve) => { resolveExternalAuth = resolve; })
    );
    const externalAuth = appLock.authenticate('Confirm to enable App Lock');

    // The blip while that call is still in flight.
    await act(async () => {
      goToBackground();
      returnToForeground();
    });
    expect(mockedAuthenticateAsync).toHaveBeenCalledTimes(1); // only the external call so far
    expect(screen.queryByText(LOCK_TEXT)).toBeNull();

    // The external call succeeds; AppLockSection would now persist the setting.
    resolveExternalAuth?.({ success: true });
    await externalAuth;
    await appLock.setEnabled(true);

    // The trailing blip, shortly after -- the exact ordering that defeated the two prior fixes,
    // which had no way to know an authenticate() call had ever happened outside AppLockGate.
    nowSpy.mockReturnValue(4_000_000 + 200);
    await act(async () => {
      goToBackground();
      returnToForeground();
    });
    expect(mockedAuthenticateAsync).toHaveBeenCalledTimes(1); // still just the external call
    expect(screen.queryByText(LOCK_TEXT)).toBeNull();

    nowSpy.mockRestore();
  });

  it('still locks on a genuine foreground return once the grace period has elapsed', async () => {
    await signIn();
    renderGate();
    expect(await screen.findByText('protected content')).toBeTruthy();

    const nowSpy = jest.spyOn(Date, 'now').mockReturnValue(5_000_000);
    mockedAuthenticateAsync.mockResolvedValueOnce({ success: true });
    await appLock.authenticate('Confirm to enable App Lock');
    await appLock.setEnabled(true);

    // Well past the grace window -- a real return, not the toggle's own trailing blip.
    nowSpy.mockReturnValue(5_000_000 + 5000);
    mockedAuthenticateAsync.mockResolvedValueOnce({ success: false, error: 'authentication_failed' });
    goToBackground();
    await act(async () => returnToForeground());

    await waitFor(() => expect(mockedAuthenticateAsync).toHaveBeenCalledTimes(2));
    expect(screen.getByText(LOCK_TEXT)).toBeTruthy();
    nowSpy.mockRestore();
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
