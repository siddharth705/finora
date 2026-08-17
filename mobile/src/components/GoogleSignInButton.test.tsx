import { render, fireEvent, waitFor } from '@testing-library/react-native';
import { GoogleSignin } from '@react-native-google-signin/google-signin';
import { GoogleSignInButton, isGoogleSignInConfigured } from './GoogleSignInButton';
import { ThemeProvider } from '../theme';

const mockedGoogleSignin = GoogleSignin as jest.Mocked<typeof GoogleSignin>;

function renderButton(onCredential = jest.fn(), onError = jest.fn()) {
  const view = render(
    <ThemeProvider>
      <GoogleSignInButton onCredential={onCredential} onError={onError} />
    </ThemeProvider>
  );
  return { view, onCredential, onError };
}

describe('GoogleSignInButton', () => {
  const originalEnv = process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID;

  afterEach(() => {
    process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID = originalEnv;
  });

  it('renders nothing when EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID is unset', () => {
    delete process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID;
    expect(isGoogleSignInConfigured()).toBe(false);

    const { view } = renderButton();
    expect(view.queryByText('Sign in with Google')).toBeNull();
  });

  describe('when configured', () => {
    beforeEach(() => {
      process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID = 'test-web-client-id.apps.googleusercontent.com';
    });

    it('renders the button', () => {
      expect(isGoogleSignInConfigured()).toBe(true);
      const { view } = renderButton();
      expect(view.getByText('Sign in with Google')).toBeTruthy();
    });

    it('hands a successful credential straight to onCredential', async () => {
      mockedGoogleSignin.signIn.mockResolvedValue({
        type: 'success',
        data: { idToken: 'a-real-looking-id-token', user: {}, scopes: [], serverAuthCode: null },
      } as never);

      const { view, onCredential } = renderButton();
      fireEvent.press(view.getByText('Sign in with Google'));

      await waitFor(() => expect(onCredential).toHaveBeenCalledWith('a-real-looking-id-token'));
      expect(mockedGoogleSignin.configure).toHaveBeenCalledWith({
        webClientId: 'test-web-client-id.apps.googleusercontent.com',
      });
    });

    it('does nothing when the user cancels -- not an error', async () => {
      mockedGoogleSignin.signIn.mockResolvedValue({ type: 'cancelled', data: null } as never);

      const { view, onCredential, onError } = renderButton();
      fireEvent.press(view.getByText('Sign in with Google'));

      await waitFor(() => expect(mockedGoogleSignin.signIn).toHaveBeenCalled());
      expect(onCredential).not.toHaveBeenCalled();
      expect(onError).not.toHaveBeenCalled();
    });

    it('reports onError when signIn throws for a reason other than cancellation', async () => {
      mockedGoogleSignin.signIn.mockRejectedValue(new Error('network down'));

      const { view, onError } = renderButton();
      fireEvent.press(view.getByText('Sign in with Google'));

      await waitFor(() =>
        expect(onError).toHaveBeenCalledWith('Sign in with Google is unavailable right now. Please try again later.')
      );
    });

    it('reports onError when a successful response is missing an idToken', async () => {
      mockedGoogleSignin.signIn.mockResolvedValue({
        type: 'success',
        data: { idToken: null, user: {}, scopes: [], serverAuthCode: null },
      } as never);

      const { view, onCredential, onError } = renderButton();
      fireEvent.press(view.getByText('Sign in with Google'));

      await waitFor(() =>
        expect(onError).toHaveBeenCalledWith('Google sign-in did not return a credential. Please try again.')
      );
      expect(onCredential).not.toHaveBeenCalled();
    });
  });
});
