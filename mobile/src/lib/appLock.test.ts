import * as LocalAuthentication from 'expo-local-authentication';
import * as SecureStore from 'expo-secure-store';
import * as appLock from './appLock';

const mockedHasHardware = LocalAuthentication.hasHardwareAsync as jest.MockedFunction<
  typeof LocalAuthentication.hasHardwareAsync
>;
const mockedIsEnrolled = LocalAuthentication.isEnrolledAsync as jest.MockedFunction<
  typeof LocalAuthentication.isEnrolledAsync
>;
const mockedAuthenticateAsync = LocalAuthentication.authenticateAsync as jest.MockedFunction<
  typeof LocalAuthentication.authenticateAsync
>;

beforeEach(() => appLock.__resetAuthenticatingStateForTests());

describe('isSupported', () => {
  it('requires both hardware and an enrolled biometric', async () => {
    mockedHasHardware.mockResolvedValueOnce(true);
    mockedIsEnrolled.mockResolvedValueOnce(true);
    expect(await appLock.isSupported()).toBe(true);
  });

  it('is false with hardware but nothing enrolled', async () => {
    mockedHasHardware.mockResolvedValueOnce(true);
    mockedIsEnrolled.mockResolvedValueOnce(false);
    expect(await appLock.isSupported()).toBe(false);
  });

  it('is false with no hardware at all', async () => {
    mockedHasHardware.mockResolvedValueOnce(false);
    mockedIsEnrolled.mockResolvedValueOnce(true);
    expect(await appLock.isSupported()).toBe(false);
  });
});

describe('isEnabled / setEnabled', () => {
  it('defaults to disabled with nothing stored', async () => {
    expect(await appLock.isEnabled()).toBe(false);
  });

  it('persists true as the exact string isEnabled checks for', async () => {
    await appLock.setEnabled(true);
    expect(await appLock.isEnabled()).toBe(true);
    expect(await SecureStore.getItemAsync('finora_app_lock_enabled')).toBe('true');
  });

  it('removes the key entirely rather than writing a false value', async () => {
    await appLock.setEnabled(true);
    await appLock.setEnabled(false);
    expect(await appLock.isEnabled()).toBe(false);
    expect(await SecureStore.getItemAsync('finora_app_lock_enabled')).toBeNull();
  });

  // D1 (Track D). "Absent" and "threw" must not collapse to the same false -- a genuinely absent
  // key means the user never turned the lock on (fine to open), but a thrown read means whether
  // the lock is on cannot actually be determined, which has to fail closed instead.
  it('fails closed to true (not the generic false-on-error safeStorage would give) when the read throws', async () => {
    const mockedGetItemAsync = SecureStore.getItemAsync as jest.MockedFunction<typeof SecureStore.getItemAsync>;
    mockedGetItemAsync.mockRejectedValueOnce(new Error('keychain unavailable'));

    expect(await appLock.isEnabled()).toBe(true);
  });
});

describe('authenticate', () => {
  it('resolves true only on a genuine successful result', async () => {
    mockedAuthenticateAsync.mockResolvedValueOnce({ success: true });
    expect(await appLock.authenticate('Unlock Fynora')).toBe(true);
  });

  it('collapses an unsuccessful result to false', async () => {
    mockedAuthenticateAsync.mockResolvedValueOnce({ success: false, error: 'authentication_failed' });
    expect(await appLock.authenticate('Unlock Fynora')).toBe(false);
  });

  it('fails closed to false if authenticateAsync itself throws', async () => {
    mockedAuthenticateAsync.mockRejectedValueOnce(new Error('hardware busy'));
    expect(await appLock.authenticate('Unlock Fynora')).toBe(false);
  });

  it('leaves the device passcode fallback enabled (does not set disableDeviceFallback)', async () => {
    mockedAuthenticateAsync.mockResolvedValueOnce({ success: true });
    await appLock.authenticate('Unlock Fynora');

    const call = mockedAuthenticateAsync.mock.calls[0][0];
    expect(call?.disableDeviceFallback).not.toBe(true);
  });
});

// isAuthenticating/justFinishedAuthenticating exist for AppLockGate's foreground listener to tell
// a self-induced AppState blip (from ANY caller's Face ID sheet, not just AppLockGate's own) apart
// from the user genuinely returning to the app -- see appLock.ts's own comment on why this has to
// be shared, module-level state rather than something scoped to one component.
describe('isAuthenticating / justFinishedAuthenticating', () => {
  afterEach(() => jest.restoreAllMocks());

  it('is false with nothing in flight and nothing ever resolved', () => {
    expect(appLock.isAuthenticating()).toBe(false);
    expect(appLock.justFinishedAuthenticating(1500)).toBe(false);
  });

  it('is true for the entire duration of an authenticate() call, false again once it resolves', async () => {
    let resolveAuth: ((result: LocalAuthentication.LocalAuthenticationResult) => void) | undefined;
    mockedAuthenticateAsync.mockImplementationOnce(
      () => new Promise((resolve) => { resolveAuth = resolve; })
    );

    const pending = appLock.authenticate('Unlock Fynora');
    expect(appLock.isAuthenticating()).toBe(true);

    resolveAuth?.({ success: true });
    await pending;
    expect(appLock.isAuthenticating()).toBe(false);
  });

  it('reports justFinishedAuthenticating for windowMs after resolving, from either outcome', async () => {
    const nowSpy = jest.spyOn(Date, 'now').mockReturnValue(1_000_000);
    mockedAuthenticateAsync.mockResolvedValueOnce({ success: false, error: 'authentication_failed' });
    await appLock.authenticate('Unlock Fynora');

    nowSpy.mockReturnValue(1_000_000 + 200);
    expect(appLock.justFinishedAuthenticating(1500)).toBe(true);

    nowSpy.mockReturnValue(1_000_000 + 1500);
    expect(appLock.justFinishedAuthenticating(1500)).toBe(false);
  });
});
