import { Text } from 'react-native';
import { act, render, waitFor, type RenderAPI } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import * as SecureStore from 'expo-secure-store';
import { AuthProvider, useAuth } from './AuthContext';
import { authApi } from '../api/endpoints';
import { registerDeviceToken, revokeDeviceToken } from '../lib/pushRegistration';
import { configureRevenueCat } from '../lib/revenueCat';

jest.mock('../api/endpoints', () => ({
  authApi: {
    login: jest.fn(),
    reactivate: jest.fn(),
    register: jest.fn(),
    google: jest.fn(),
    apple: jest.fn(),
    logout: jest.fn(async () => ({ message: 'ok' })),
  },
}));

// Task 14. Pins the wiring in AuthContext.tsx itself (which hooks/setPhoneVerified()/persist()/
// logout()) call registerDeviceToken()/revokeDeviceToken(), and in what order relative to
// storage -- without this, dropping the phoneVerified gate or moving the revoke call after the
// stored token is cleared would both ship green: the former is a guaranteed 403
// PHONE_VERIFICATION_REQUIRED on every app open (/api/v1/device-tokens is not exempt in the
// backend's PhoneVerificationFilter), the latter a guaranteed 401 that silently leaves the token
// registered server-side. See pushRegistration.test.ts for that module's own behavior in
// isolation; this file only needs to know AuthContext calls it, and when.
jest.mock('../lib/pushRegistration', () => ({
  registerDeviceToken: jest.fn(),
  revokeDeviceToken: jest.fn(),
}));

// Subscription billing V4 (design spec §2/§6.1 step 1): configureRevenueCat() must run once the
// real Fynora user id is known, whether that's a fresh login/register/etc. or a cold-start
// restore of an already-persisted session -- see AuthContext bootstrap/configureRevenueCat below.
jest.mock('../lib/revenueCat', () => ({
  configureRevenueCat: jest.fn(),
}));

const mockedAuthApi = authApi as jest.Mocked<typeof authApi>;
const mockedRegisterDeviceToken = registerDeviceToken as jest.MockedFunction<typeof registerDeviceToken>;
const mockedRevokeDeviceToken = revokeDeviceToken as jest.MockedFunction<typeof revokeDeviceToken>;
const mockedConfigureRevenueCat = configureRevenueCat as jest.MockedFunction<typeof configureRevenueCat>;

const SESSION = {
  id: 'user-abc-123',
  token: 'access-token',
  refreshToken: 'refresh-token',
  email: 'someone@example.com',
  fullName: 'Some One',
  phoneVerified: true,
  maskedPhone: '+•••••••••210',
  onboardingCompleted: true,
};

/** Renders context state so assertions read against what a screen would actually see. */
function Probe() {
  const { bootstrapping, token, email, phoneVerified, onboardingCompleted } = useAuth();
  return (
    <>
      <Text testID="bootstrapping">{String(bootstrapping)}</Text>
      <Text testID="token">{token ?? 'none'}</Text>
      <Text testID="email">{email ?? 'none'}</Text>
      <Text testID="phoneVerified">{String(phoneVerified)}</Text>
      <Text testID="onboardingCompleted">{String(onboardingCompleted)}</Text>
    </>
  );
}

let auth: ReturnType<typeof useAuth>;
function Capture() {
  auth = useAuth();
  return null;
}

function renderAuth(): RenderAPI {
  // AuthProvider reads the QueryClient so logout can clear cached financial data -- see its own
  // comment, and logoutCacheIsolation.test.tsx. App.tsx already nests it this way; wrapping here
  // keeps the harness matching the real composition rather than testing a shape that never ships.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <Probe />
        <Capture />
      </AuthProvider>
    </QueryClientProvider>
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
    // Opposite default from phoneVerified: a missing value means "not onboarded", not "onboarded".
    expect(view.getByTestId('onboardingCompleted')).toHaveTextContent('false');
  });

  it('restores a persisted onboardingCompleted=true', async () => {
    await SecureStore.setItemAsync('finora_token', 'stored-token');
    await SecureStore.setItemAsync('finora_onboarding_completed', 'true');

    const view = renderAuth();
    await settle(view);

    expect(view.getByTestId('onboardingCompleted')).toHaveTextContent('true');
  });

  it('configures RevenueCat with the restored user id -- a cold start on an already-signed-in device', async () => {
    await SecureStore.setItemAsync('finora_token', 'stored-token');
    await SecureStore.setItemAsync('finora_user_id', 'user-restored-456');

    const view = renderAuth();
    await settle(view);

    expect(mockedConfigureRevenueCat).toHaveBeenCalledWith('user-restored-456');
  });

  it('does not configure RevenueCat when there is no stored session to restore', async () => {
    const view = renderAuth();
    await settle(view);

    expect(mockedConfigureRevenueCat).not.toHaveBeenCalled();
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
    expect(view.getByTestId('onboardingCompleted')).toHaveTextContent('true');
    expect(await SecureStore.getItemAsync('finora_onboarding_completed')).toBe('true');
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

  it('configures RevenueCat with the signed-in user id', async () => {
    mockedAuthApi.login.mockResolvedValue({ data: SESSION } as never);
    const view = renderAuth();
    await settle(view);

    await act(async () => {
      await auth.login('someone@example.com', 'pw');
    });

    expect(mockedConfigureRevenueCat).toHaveBeenCalledWith('user-abc-123');
  });
});

describe('AuthContext reactivate', () => {
  it('persists the session and reports the verified flag, same as login', async () => {
    mockedAuthApi.reactivate.mockResolvedValue({ data: SESSION } as never);
    const view = renderAuth();
    await settle(view);

    let verified: boolean | undefined;
    await act(async () => {
      verified = await auth.reactivate('reactivation-token');
    });

    expect(mockedAuthApi.reactivate).toHaveBeenCalledWith('reactivation-token');
    expect(verified).toBe(true);
    expect(view.getByTestId('token')).toHaveTextContent('access-token');
    expect(await SecureStore.getItemAsync('finora_token')).toBe('access-token');
    expect(await SecureStore.getItemAsync('finora_refresh_token')).toBe('refresh-token');
  });

  it('leaves state and storage untouched when the token is stale or already used', async () => {
    mockedAuthApi.reactivate.mockRejectedValue(new Error('expired token'));
    const view = renderAuth();
    await settle(view);

    await act(async () => {
      await expect(auth.reactivate('stale-token')).rejects.toThrow();
    });

    expect(view.getByTestId('token')).toHaveTextContent('none');
    expect(await SecureStore.getItemAsync('finora_token')).toBeNull();
  });
});

describe('AuthContext loginWithGoogle', () => {
  it('persists the session and reports the verified flag, same as login()', async () => {
    mockedAuthApi.google.mockResolvedValue({ data: SESSION } as never);
    const view = renderAuth();
    await settle(view);

    let verified: boolean | undefined;
    await act(async () => {
      verified = await auth.loginWithGoogle('a-google-id-token');
    });

    expect(mockedAuthApi.google).toHaveBeenCalledWith('a-google-id-token');
    expect(verified).toBe(true);
    expect(view.getByTestId('token')).toHaveTextContent('access-token');
    expect(await SecureStore.getItemAsync('finora_token')).toBe('access-token');
  });

  it('propagates a rejection (e.g. an invalid/expired credential) without touching state', async () => {
    mockedAuthApi.google.mockRejectedValue(new Error('invalid token'));
    const view = renderAuth();
    await settle(view);

    await act(async () => {
      await expect(auth.loginWithGoogle('a-bad-id-token')).rejects.toThrow();
    });

    expect(view.getByTestId('token')).toHaveTextContent('none');
  });
});

describe('AuthContext loginWithApple', () => {
  it('forwards the client-captured fullName straight through to authApi.apple', async () => {
    mockedAuthApi.apple.mockResolvedValue({ data: SESSION } as never);
    const view = renderAuth();
    await settle(view);

    await act(async () => {
      await auth.loginWithApple('an-apple-id-token', 'Amy Santiago');
    });

    expect(mockedAuthApi.apple).toHaveBeenCalledWith('an-apple-id-token', 'Amy Santiago');
    expect(view.getByTestId('token')).toHaveTextContent('access-token');
  });

  it('works with no fullName -- every sign-in after the first, when Apple gives none', async () => {
    mockedAuthApi.apple.mockResolvedValue({ data: SESSION } as never);
    const view = renderAuth();
    await settle(view);

    await act(async () => {
      await auth.loginWithApple('an-apple-id-token', undefined);
    });

    expect(mockedAuthApi.apple).toHaveBeenCalledWith('an-apple-id-token', undefined);
    expect(view.getByTestId('token')).toHaveTextContent('access-token');
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
    expect(view.getByTestId('onboardingCompleted')).toHaveTextContent('false');
    await waitFor(async () => {
      expect(await SecureStore.getItemAsync('finora_token')).toBeNull();
      expect(await SecureStore.getItemAsync('finora_refresh_token')).toBeNull();
      expect(await SecureStore.getItemAsync('finora_onboarding_completed')).toBeNull();
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

describe('AuthContext setOnboardingCompleted', () => {
  it('flips the flag and persists it', async () => {
    const view = renderAuth();
    await settle(view);
    expect(view.getByTestId('onboardingCompleted')).toHaveTextContent('false');

    await act(async () => {
      auth.setOnboardingCompleted(true);
    });

    expect(view.getByTestId('onboardingCompleted')).toHaveTextContent('true');
    await waitFor(async () => {
      expect(await SecureStore.getItemAsync('finora_onboarding_completed')).toBe('true');
    });
  });
});

describe('AuthContext push registration wiring (Task 14)', () => {
  it('does not register a device token when login returns an unverified phone', async () => {
    mockedAuthApi.login.mockResolvedValue({ data: { ...SESSION, phoneVerified: false } } as never);
    const view = renderAuth();
    await settle(view);

    await act(async () => {
      await auth.login('someone@example.com', 'pw');
    });

    // /api/v1/device-tokens is not exempt in PhoneVerificationFilter -- calling this before
    // verification actually completes is a guaranteed 403. setPhoneVerified() below is where a
    // brand-new session's very first registration attempt belongs instead.
    expect(mockedRegisterDeviceToken).not.toHaveBeenCalled();
  });

  it('registers a device token once phone verification completes', async () => {
    mockedAuthApi.login.mockResolvedValue({ data: { ...SESSION, phoneVerified: false } } as never);
    const view = renderAuth();
    await settle(view);
    await act(async () => {
      await auth.login('someone@example.com', 'pw');
    });

    await act(async () => {
      auth.setPhoneVerified(true);
    });

    expect(mockedRegisterDeviceToken).toHaveBeenCalledTimes(1);
  });

  it('registers a device token for a returning, already-verified login', async () => {
    mockedAuthApi.login.mockResolvedValue({ data: SESSION } as never); // SESSION.phoneVerified === true
    const view = renderAuth();
    await settle(view);

    await act(async () => {
      await auth.login('someone@example.com', 'pw');
    });

    expect(mockedRegisterDeviceToken).toHaveBeenCalledTimes(1);
  });

  it('revokes the device token before the stored auth token is cleared on logout', async () => {
    mockedAuthApi.login.mockResolvedValue({ data: SESSION } as never);
    // Records whether the bearer token was still readable from storage at the moment
    // revokeDeviceToken() was actually invoked -- the real function's own POST needs it there to
    // authenticate itself (see pushRegistration.ts), so this pins the ORDERING logout() depends
    // on, not merely that both things eventually happened.
    let tokenPresentAtRevokeTime: string | null = null;
    mockedRevokeDeviceToken.mockImplementation(async () => {
      tokenPresentAtRevokeTime = await SecureStore.getItemAsync('finora_token');
    });
    const view = renderAuth();
    await settle(view);
    await act(async () => {
      await auth.login('someone@example.com', 'pw');
    });

    await act(async () => {
      auth.logout();
    });

    await waitFor(async () => {
      expect(await SecureStore.getItemAsync('finora_token')).toBeNull();
    });
    expect(mockedRevokeDeviceToken).toHaveBeenCalledTimes(1);
    expect(tokenPresentAtRevokeTime).toBe('access-token');
  });
});
