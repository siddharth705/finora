import { Text } from 'react-native';
import { act, render, waitFor, type RenderAPI } from '@testing-library/react-native';
import * as SecureStore from 'expo-secure-store';
import { AuthProvider, useAuth } from './AuthContext';
import { authApi } from '../api/endpoints';

jest.mock('../api/endpoints', () => ({
  authApi: {
    login: jest.fn(),
    register: jest.fn(),
    logout: jest.fn(async () => ({ message: 'ok' })),
  },
}));

const mockedAuthApi = authApi as jest.Mocked<typeof authApi>;

const SESSION = {
  token: 'access-token',
  refreshToken: 'refresh-token',
  email: 'someone@example.com',
  fullName: 'Some One',
  phoneVerified: true,
  maskedPhone: '+•••••••••210',
};

/** Renders context state so assertions read against what a screen would actually see. */
function Probe() {
  const { bootstrapping, token, email, phoneVerified } = useAuth();
  return (
    <>
      <Text testID="bootstrapping">{String(bootstrapping)}</Text>
      <Text testID="token">{token ?? 'none'}</Text>
      <Text testID="email">{email ?? 'none'}</Text>
      <Text testID="phoneVerified">{String(phoneVerified)}</Text>
    </>
  );
}

let auth: ReturnType<typeof useAuth>;
function Capture() {
  auth = useAuth();
  return null;
}

function renderAuth(): RenderAPI {
  return render(
    <AuthProvider>
      <Probe />
      <Capture />
    </AuthProvider>
  );
}

/** Waits for the async SecureStore restore to finish. */
async function settle(view: RenderAPI) {
  await waitFor(() => expect(view.getByTestId('bootstrapping')).toHaveTextContent('false'));
}

describe('AuthContext bootstrap', () => {
  /**
   * The reason mobile diverges from web here at all: localStorage reads are synchronous, so the
   * web version seeds state in useState initializers. SecureStore's are not. Without the
   * bootstrapping flag, a cold start renders token === null for a frame and RootNavigator shows
   * Login to an already-signed-in user.
   */
  it('starts in a bootstrapping state rather than reporting signed-out', async () => {
    const view = renderAuth();
    expect(view.getByTestId('bootstrapping')).toHaveTextContent('true');
    await settle(view); // avoid an unawaited state update after the test ends
  });

  it('restores a persisted session', async () => {
    await SecureStore.setItemAsync('finora_token', 'stored-token');
    await SecureStore.setItemAsync('finora_email', 'stored@example.com');
    await SecureStore.setItemAsync('finora_phone_verified', 'true');

    const view = renderAuth();
    await settle(view);

    expect(view.getByTestId('token')).toHaveTextContent('stored-token');
    expect(view.getByTestId('email')).toHaveTextContent('stored@example.com');
    expect(view.getByTestId('phoneVerified')).toHaveTextContent('true');
  });

  it('finishes bootstrapping with no stored session', async () => {
    const view = renderAuth();
    await settle(view);
    expect(view.getByTestId('token')).toHaveTextContent('none');
  });

  // Stored as the string 'true'/'false'; anything else must not read as verified.
  it('treats a non-"true" verified flag as unverified', async () => {
    await SecureStore.setItemAsync('finora_token', 't');
    await SecureStore.setItemAsync('finora_phone_verified', 'false');

    const view = renderAuth();
    await settle(view);
    expect(view.getByTestId('phoneVerified')).toHaveTextContent('false');
  });
});

describe('AuthContext login', () => {
  it('persists every session key and reports the verified flag', async () => {
    mockedAuthApi.login.mockResolvedValue({ data: SESSION } as never);
    const view = renderAuth();
    await settle(view);

    let verified: boolean | undefined;
    await act(async () => {
      verified = await auth.login('someone@example.com', 'pw');
    });

    expect(verified).toBe(true);
    expect(view.getByTestId('token')).toHaveTextContent('access-token');
    expect(await SecureStore.getItemAsync('finora_token')).toBe('access-token');
    expect(await SecureStore.getItemAsync('finora_refresh_token')).toBe('refresh-token');
    expect(await SecureStore.getItemAsync('finora_phone_verified')).toBe('true');
  });

  it('reports an unverified account so the navigator can route to verification', async () => {
    mockedAuthApi.login.mockResolvedValue({ data: { ...SESSION, phoneVerified: false } } as never);
    const view = renderAuth();
    await settle(view);

    let verified: boolean | undefined;
    await act(async () => {
      verified = await auth.login('someone@example.com', 'pw');
    });

    expect(verified).toBe(false);
    expect(view.getByTestId('phoneVerified')).toHaveTextContent('false');
  });

  it('leaves state and storage untouched when the call fails', async () => {
    mockedAuthApi.login.mockRejectedValue(new Error('bad credentials'));
    const view = renderAuth();
    await settle(view);

    await act(async () => {
      await expect(auth.login('someone@example.com', 'wrong')).rejects.toThrow();
    });

    expect(view.getByTestId('token')).toHaveTextContent('none');
    expect(await SecureStore.getItemAsync('finora_token')).toBeNull();
  });
});

describe('AuthContext logout', () => {
  it('clears state and storage, and revokes the refresh token server-side', async () => {
    mockedAuthApi.login.mockResolvedValue({ data: SESSION } as never);
    const view = renderAuth();
    await settle(view);
    await act(async () => {
      await auth.login('someone@example.com', 'pw');
    });

    await act(async () => {
      auth.logout();
    });

    expect(view.getByTestId('token')).toHaveTextContent('none');
    expect(view.getByTestId('phoneVerified')).toHaveTextContent('false');
    await waitFor(async () => {
      expect(await SecureStore.getItemAsync('finora_token')).toBeNull();
      expect(await SecureStore.getItemAsync('finora_refresh_token')).toBeNull();
    });
    // Best-effort revoke -- and it must read the refresh token before deletion races it.
    expect(mockedAuthApi.logout).toHaveBeenCalledWith('refresh-token');
  });

  it('still signs the user out locally when the revoke call fails', async () => {
    mockedAuthApi.login.mockResolvedValue({ data: SESSION } as never);
    mockedAuthApi.logout.mockRejectedValue(new Error('offline'));
    const view = renderAuth();
    await settle(view);
    await act(async () => {
      await auth.login('someone@example.com', 'pw');
    });

    await act(async () => {
      auth.logout();
    });

    expect(view.getByTestId('token')).toHaveTextContent('none');
    await waitFor(async () => {
      expect(await SecureStore.getItemAsync('finora_token')).toBeNull();
    });
  });
});

describe('AuthContext setPhoneVerified', () => {
  it('flips the flag and persists it -- this is what moves the navigator into the app', async () => {
    mockedAuthApi.login.mockResolvedValue({ data: { ...SESSION, phoneVerified: false } } as never);
    const view = renderAuth();
    await settle(view);
    await act(async () => {
      await auth.login('someone@example.com', 'pw');
    });

    await act(async () => {
      auth.setPhoneVerified(true);
    });

    expect(view.getByTestId('phoneVerified')).toHaveTextContent('true');
    await waitFor(async () => {
      expect(await SecureStore.getItemAsync('finora_phone_verified')).toBe('true');
    });
  });
});
