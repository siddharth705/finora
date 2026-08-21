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
});

describe('authenticate', () => {
  it('resolves true only on a genuine successful result', async () => {
    mockedAuthenticateAsync.mockResolvedValueOnce({ success: true });
    expect(await appLock.authenticate('Unlock Finora')).toBe(true);
  });

  it('collapses an unsuccessful result to false', async () => {
    mockedAuthenticateAsync.mockResolvedValueOnce({ success: false, error: 'authentication_failed' });
    expect(await appLock.authenticate('Unlock Finora')).toBe(false);
  });

  it('fails closed to false if authenticateAsync itself throws', async () => {
    mockedAuthenticateAsync.mockRejectedValueOnce(new Error('hardware busy'));
    expect(await appLock.authenticate('Unlock Finora')).toBe(false);
  });

  it('leaves the device passcode fallback enabled (does not set disableDeviceFallback)', async () => {
    mockedAuthenticateAsync.mockResolvedValueOnce({ success: true });
    await appLock.authenticate('Unlock Finora');

    const call = mockedAuthenticateAsync.mock.calls[0][0];
    expect(call?.disableDeviceFallback).not.toBe(true);
  });
});
