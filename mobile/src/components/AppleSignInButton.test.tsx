import { Platform } from 'react-native';
import { render, fireEvent, waitFor } from '@testing-library/react-native';
import * as AppleAuthentication from 'expo-apple-authentication';
import { AppleSignInButton } from './AppleSignInButton';
import { ThemeProvider } from '../theme';

const mockedSignInAsync = AppleAuthentication.signInAsync as jest.Mock;

function renderButton(onCredential = jest.fn(), onError = jest.fn()) {
  const view = render(
    <ThemeProvider>
      <AppleSignInButton onCredential={onCredential} onError={onError} />
    </ThemeProvider>
  );
  return { view, onCredential, onError };
}

describe('AppleSignInButton', () => {
  const originalOS = Platform.OS;

  afterEach(() => {
    Platform.OS = originalOS;
  });

  it('renders nothing on Android -- iOS only, per D-26', () => {
    Platform.OS = 'android';
    const { view } = renderButton();
    expect(view.queryByText('Sign in with Apple')).toBeNull();
  });

  describe('on iOS', () => {
    beforeEach(() => {
      Platform.OS = 'ios';
    });

    it('renders the button', () => {
      const { view } = renderButton();
      expect(view.getByText('Sign in with Apple')).toBeTruthy();
    });

    it('captures fullName on first authorization and forwards both to onCredential', async () => {
      mockedSignInAsync.mockResolvedValue({
        identityToken: 'a-real-looking-apple-id-token',
        fullName: { givenName: 'Amy', familyName: 'Santiago' },
        email: 'amy@example.test',
        user: 'apple-sub-123',
        state: null,
        realUserStatus: 2,
        authorizationCode: null,
      });

      const { view, onCredential } = renderButton();
      fireEvent.press(view.getByText('Sign in with Apple'));

      await waitFor(() =>
        expect(onCredential).toHaveBeenCalledWith('a-real-looking-apple-id-token', 'Amy Santiago')
      );
    });

    it('forwards undefined, not null, when Apple returns no name -- every sign-in after the first', async () => {
      mockedSignInAsync.mockResolvedValue({
        identityToken: 'a-real-looking-apple-id-token',
        fullName: null,
        email: null,
        user: 'apple-sub-123',
        state: null,
        realUserStatus: 2,
        authorizationCode: null,
      });

      const { view, onCredential } = renderButton();
      fireEvent.press(view.getByText('Sign in with Apple'));

      await waitFor(() =>
        expect(onCredential).toHaveBeenCalledWith('a-real-looking-apple-id-token', undefined)
      );
    });

    it('does nothing when the user cancels -- not an error', async () => {
      const cancelError = Object.assign(new Error('canceled'), { code: 'ERR_REQUEST_CANCELED' });
      mockedSignInAsync.mockRejectedValue(cancelError);

      const { view, onCredential, onError } = renderButton();
      fireEvent.press(view.getByText('Sign in with Apple'));

      await waitFor(() => expect(mockedSignInAsync).toHaveBeenCalled());
      expect(onCredential).not.toHaveBeenCalled();
      expect(onError).not.toHaveBeenCalled();
    });

    it('reports onError when signInAsync throws for a reason other than cancellation', async () => {
      mockedSignInAsync.mockRejectedValue(new Error('something else went wrong'));

      const { view, onError } = renderButton();
      fireEvent.press(view.getByText('Sign in with Apple'));

      await waitFor(() =>
        expect(onError).toHaveBeenCalledWith('Sign in with Apple is unavailable right now. Please try again later.')
      );
    });

    it('reports onError when the response is missing an identityToken', async () => {
      mockedSignInAsync.mockResolvedValue({
        identityToken: null,
        fullName: null,
        email: null,
        user: 'apple-sub-123',
        state: null,
        realUserStatus: 2,
        authorizationCode: null,
      });

      const { view, onCredential, onError } = renderButton();
      fireEvent.press(view.getByText('Sign in with Apple'));

      await waitFor(() =>
        expect(onError).toHaveBeenCalledWith('Apple sign-in did not return a credential. Please try again.')
      );
      expect(onCredential).not.toHaveBeenCalled();
    });
  });
});
