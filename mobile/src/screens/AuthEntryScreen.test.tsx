import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { AuthEntryScreen } from './AuthEntryScreen';
import { authApi } from '../api/endpoints';
import { ThemeProvider } from '../theme';
import type { AuthStackParamList } from '../navigation/types';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';

const mockLoginWithGoogle = jest.fn();
const mockLoginWithApple = jest.fn();
const mockReactivate = jest.fn();
jest.mock('../context/AuthContext', () => ({
  useAuth: () => ({
    loginWithGoogle: mockLoginWithGoogle,
    loginWithApple: mockLoginWithApple,
    reactivate: mockReactivate,
  }),
}));

jest.mock('../api/endpoints', () => ({
  authApi: { identify: jest.fn() },
}));

type Props = NativeStackScreenProps<AuthStackParamList, 'AuthEntry'>;

const mockNavigate = jest.fn();

function renderScreen() {
  const navigation = { navigate: mockNavigate } as unknown as Props['navigation'];
  const route = { key: 'AuthEntry', name: 'AuthEntry', params: undefined } as Props['route'];
  return render(
    <ThemeProvider>
      <AuthEntryScreen navigation={navigation} route={route} />
    </ThemeProvider>
  );
}

function serverError(message: string) {
  return Object.assign(new Error('Request failed'), {
    isAxiosError: true,
    response: { status: 429, data: { message } },
  });
}

async function settle() {
  await act(async () => {});
}

describe('AuthEntryScreen', () => {
  beforeEach(() => {
    mockNavigate.mockReset();
    jest.mocked(authApi.identify).mockReset();
  });

  it('shows a validation error and makes no API call when submitted empty', async () => {
    renderScreen();

    fireEvent.press(screen.getByRole('button', { name: 'Continue' }));
    await settle();

    expect(screen.getByText('Enter your email or mobile number.')).toBeTruthy();
    expect(authApi.identify).not.toHaveBeenCalled();
  });

  it('navigates to Login with the identifier prefilled when nextAction is EXISTS', async () => {
    jest.mocked(authApi.identify).mockResolvedValue({ nextAction: 'EXISTS' });
    renderScreen();

    fireEvent.changeText(screen.getByLabelText('Email or mobile number'), 'jane@example.com');
    fireEvent.press(screen.getByRole('button', { name: 'Continue' }));
    await settle();

    expect(authApi.identify).toHaveBeenCalledWith('jane@example.com');
    await waitFor(() =>
      expect(mockNavigate).toHaveBeenCalledWith('Login', { identifier: 'jane@example.com' })
    );
  });

  it('navigates to Register with the email prefilled when nextAction is CONTINUE and the identifier looks like an email', async () => {
    jest.mocked(authApi.identify).mockResolvedValue({ nextAction: 'CONTINUE' });
    renderScreen();

    fireEvent.changeText(screen.getByLabelText('Email or mobile number'), 'newuser@example.com');
    fireEvent.press(screen.getByRole('button', { name: 'Continue' }));
    await settle();

    await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith('Register', { email: 'newuser@example.com' }));
  });

  it('navigates to Register with the phone number prefilled when nextAction is CONTINUE and the identifier looks like a phone number', async () => {
    jest.mocked(authApi.identify).mockResolvedValue({ nextAction: 'CONTINUE' });
    renderScreen();

    const fakePhone = '+919876543210'; // synthetic-ok: same fake sequential number used throughout this app's test fixtures
    fireEvent.changeText(screen.getByLabelText('Email or mobile number'), fakePhone);
    fireEvent.press(screen.getByRole('button', { name: 'Continue' }));
    await settle();

    await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith('Register', { phoneNumber: fakePhone }));
  });

  it('shows a server error message and does not navigate when identify() fails', async () => {
    jest.mocked(authApi.identify).mockRejectedValue(serverError('Too many attempts. Try again later.'));
    renderScreen();

    fireEvent.changeText(screen.getByLabelText('Email or mobile number'), 'jane@example.com');
    fireEvent.press(screen.getByRole('button', { name: 'Continue' }));
    await settle();

    expect(screen.getByText('Too many attempts. Try again later.')).toBeTruthy();
    expect(mockNavigate).not.toHaveBeenCalled();
  });
});
