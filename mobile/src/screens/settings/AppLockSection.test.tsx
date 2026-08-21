import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import * as LocalAuthentication from 'expo-local-authentication';
import * as SecureStore from 'expo-secure-store';
import { AppLockSection } from './AppLockSection';
import { ThemeProvider } from '../../theme';

const mockedHasHardware = LocalAuthentication.hasHardwareAsync as jest.MockedFunction<
  typeof LocalAuthentication.hasHardwareAsync
>;
const mockedIsEnrolled = LocalAuthentication.isEnrolledAsync as jest.MockedFunction<
  typeof LocalAuthentication.isEnrolledAsync
>;
const mockedAuthenticateAsync = LocalAuthentication.authenticateAsync as jest.MockedFunction<
  typeof LocalAuthentication.authenticateAsync
>;

function renderSection() {
  return render(
    <ThemeProvider>
      <AppLockSection />
    </ThemeProvider>
  );
}

describe('AppLockSection', () => {
  it('explains why the toggle is unavailable on unsupported hardware', async () => {
    mockedHasHardware.mockResolvedValueOnce(false);
    mockedIsEnrolled.mockResolvedValueOnce(false);
    renderSection();

    expect(await screen.findByText(/Set up a fingerprint, face, or passcode/i)).toBeTruthy();
    expect(screen.queryByLabelText('App Lock')).toBeNull();
  });

  it('requires a real successful authentication before turning on', async () => {
    mockedHasHardware.mockResolvedValueOnce(true);
    mockedIsEnrolled.mockResolvedValueOnce(true);
    mockedAuthenticateAsync.mockResolvedValueOnce({ success: false, error: 'authentication_failed' });
    renderSection();

    const toggle = await screen.findByLabelText('App Lock');
    expect(toggle.props.value).toBe(false);

    await act(async () => fireEvent(toggle, 'valueChange', true));

    // A failed confirmation must not have persisted the setting.
    expect(toggle.props.value).toBe(false);
    expect(await SecureStore.getItemAsync('finora_app_lock_enabled')).toBeNull();
    expect(screen.getByText(/App Lock was not enabled/i)).toBeTruthy();
  });

  it('turns on only after a successful confirmation', async () => {
    mockedHasHardware.mockResolvedValueOnce(true);
    mockedIsEnrolled.mockResolvedValueOnce(true);
    mockedAuthenticateAsync.mockResolvedValueOnce({ success: true });
    renderSection();

    const toggle = await screen.findByLabelText('App Lock');
    await act(async () => fireEvent(toggle, 'valueChange', true));

    await waitFor(async () =>
      expect(await SecureStore.getItemAsync('finora_app_lock_enabled')).toBe('true')
    );
    expect(toggle.props.value).toBe(true);
  });

  it('turns off without requiring authentication', async () => {
    await SecureStore.setItemAsync('finora_app_lock_enabled', 'true');
    mockedHasHardware.mockResolvedValueOnce(true);
    mockedIsEnrolled.mockResolvedValueOnce(true);
    renderSection();

    const toggle = await screen.findByLabelText('App Lock');
    expect(toggle.props.value).toBe(true);

    await act(async () => fireEvent(toggle, 'valueChange', false));

    expect(mockedAuthenticateAsync).not.toHaveBeenCalled();
    expect(toggle.props.value).toBe(false);
    expect(await SecureStore.getItemAsync('finora_app_lock_enabled')).toBeNull();
  });
});
