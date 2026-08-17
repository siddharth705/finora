import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { LoginScreen } from './LoginScreen';
import { AUTH_ACCOUNT_DEACTIVATED } from '../api/errorCodes';
import { ThemeProvider } from '../theme';
import type { AuthStackParamList } from '../navigation/types';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';

/**
 * Scoped to the reactivation flow -- the one piece of LoginScreen's logic this test file exists
 * for. Plain sign-in (identifier/password submit) has no behaviour of its own beyond what
 * AuthContext.test.tsx already covers on the login() side.
 */

const mockLogin = jest.fn();
const mockReactivate = jest.fn();
const mockLoginWithGoogle = jest.fn();
const mockLoginWithApple = jest.fn();
jest.mock('../context/AuthContext', () => ({
  useAuth: () => ({
    login: mockLogin,
    reactivate: mockReactivate,
    loginWithGoogle: mockLoginWithGoogle,
    loginWithApple: mockLoginWithApple,
  }),
}));

type Props = NativeStackScreenProps<AuthStackParamList, 'Login'>;

const mockNavigate = jest.fn();

// D-23 Phase 2 / D-26: LoginScreen now also renders GoogleSignInButton/AppleSignInButton, both of
// which read the resolved theme via useThemeSetting() -- without a real provider here they throw
// "must be used within ThemeProvider" before this file's own reactivation-flow assertions ever run.
function renderScreen() {
  const navigation = { navigate: mockNavigate } as unknown as Props['navigation'];
  const route = { key: 'Login', name: 'Login', params: undefined } as Props['route'];
  return render(
    <ThemeProvider>
      <LoginScreen navigation={navigation} route={route} />
    </ThemeProvider>
  );
}

/** Matches what apiErrorCode()/apiErrorDetails() read: an axios error carrying both. */
function deactivatedError(reactivationToken: string) {
  return Object.assign(new Error('Request failed'), {
    isAxiosError: true,
    response: {
      status: 403,
      data: {
        errorCode: AUTH_ACCOUNT_DEACTIVATED,
        message: 'This account is deactivated.',
        details: { reactivationToken },
      },
    },
  });
}

function serverError(message: string) {
  return Object.assign(new Error('Request failed'), {
    isAxiosError: true,
    response: { status: 400, data: { message } },
  });
}

/** Lets handleSubmit/handleReactivate's `finally` setState land before assertions run. */
async function settle() {
  await act(async () => {});
}

async function fillAndSubmit() {
  fireEvent.changeText(screen.getByLabelText('Email or mobile number'), 'someone@example.com');
  fireEvent.changeText(screen.getByLabelText('Password'), 'correct-password');
  // getByText('Sign in') is ambiguous -- it also matches the screen title -- so this goes through
  // the accessibility role every shared Button carries instead.
  fireEvent.press(screen.getByRole('button', { name: 'Sign in' }));
  await settle();
}

describe('LoginScreen reactivation', () => {
  beforeEach(() => {
    mockLogin.mockReset();
    mockReactivate.mockReset();
    mockNavigate.mockReset();
  });

  it('shows the reactivation prompt when login reports AUTH_ACCOUNT_DEACTIVATED', async () => {
    mockLogin.mockRejectedValue(deactivatedError('reactivation-token'));
    renderScreen();

    await fillAndSubmit();

    expect(screen.getByText('Welcome back')).toBeTruthy();
    expect(screen.getByText('Reactivate my account')).toBeTruthy();
    // The plain sign-in form is gone -- this is a replacement step, not an overlay.
    expect(screen.queryByLabelText('Password')).toBeNull();
  });

  it('completes sign-in with the token the server issued, not a stale one', async () => {
    mockLogin.mockRejectedValue(deactivatedError('the-real-token'));
    mockReactivate.mockResolvedValue(true);
    renderScreen();
    await fillAndSubmit();

    fireEvent.press(screen.getByText('Reactivate my account'));
    await settle();

    expect(mockReactivate).toHaveBeenCalledWith('the-real-token');
  });

  it('shows an error when reactivation fails', async () => {
    mockLogin.mockRejectedValue(deactivatedError('reactivation-token'));
    mockReactivate.mockRejectedValue(serverError('This reactivation link has already been used.'));
    renderScreen();
    await fillAndSubmit();

    fireEvent.press(screen.getByText('Reactivate my account'));
    await settle();

    expect(screen.getByText('This reactivation link has already been used.')).toBeTruthy();
  });

  it('clears reactivationToken when the user cancels', async () => {
    mockLogin.mockRejectedValue(deactivatedError('reactivation-token'));
    renderScreen();
    await fillAndSubmit();

    fireEvent.press(screen.getByText('Not you? Go back'));
    await settle();

    expect(screen.getByLabelText('Email or mobile number')).toBeTruthy();
    expect(screen.queryByText('Welcome back')).toBeNull();
  });

  /**
   * Regression test for the bug fixed alongside this file: a failed reactivation attempt used to
   * leave its error message in state, so cancelling and triggering a brand-new reactivation prompt
   * (even for a different token) showed that stale failure before the user had clicked anything on
   * the new attempt. Unlike the web app's ReactivateAccountPrompt (a separate component whose own
   * error state resets on unmount), this screen's reactivation step is an inline early return in
   * the same long-lived component, so nothing clears it for free.
   */
  it('does not carry a failed attempt’s error into a fresh reactivation prompt', async () => {
    mockLogin.mockRejectedValueOnce(deactivatedError('first-token'));
    mockReactivate.mockRejectedValueOnce(serverError('This reactivation link has already been used.'));
    renderScreen();
    await fillAndSubmit();

    fireEvent.press(screen.getByText('Reactivate my account'));
    await settle();
    expect(screen.getByText('This reactivation link has already been used.')).toBeTruthy();

    fireEvent.press(screen.getByText('Not you? Go back'));
    await settle();

    mockLogin.mockRejectedValueOnce(deactivatedError('second-token'));
    await fillAndSubmit();

    expect(screen.getByText('Welcome back')).toBeTruthy();
    expect(screen.queryByText('This reactivation link has already been used.')).toBeNull();
  });

  it('navigates to Register from the sign-in form', async () => {
    renderScreen();

    fireEvent.press(screen.getByText('Register'));
    await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith('Register'));
  });
});
