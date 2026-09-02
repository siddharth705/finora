import { registerDeviceToken, revokeDeviceToken } from './pushRegistration';

// '@react-native-firebase/messaging' is mocked globally in src/test/setup.ts (same posture as
// '@react-native-firebase/auth' there -- no native app registered under the runner, plus its real
// entry point is ESM source that a bare automock can't introspect without throwing). Nothing below
// exercises that default module mock anyway: every call here passes its own `messaging` fake
// through registerDeviceToken()/revokeDeviceToken()'s dependency-injection parameter instead.

// Mirrors @react-native-firebase/messaging's AuthorizationStatus enum -- see pushRegistration.ts's
// own comment on why that enum is duplicated as plain numbers rather than imported.
const AUTHORIZED = 1;
const DENIED = 0;

/**
 * Builds a fake messaging module shaped like PushMessaging (requestPermission/getToken/
 * onTokenRefresh), plus a test-only __emitTokenRefresh to simulate Firebase rotating the token.
 * onTokenRefresh's listener is invoked synchronously by __emitTokenRefresh, matching how the real
 * native event emitter delivers it -- so a test can assert on postDeviceToken immediately after
 * calling __emitTokenRefresh with no extra await.
 */
function requestPermissionMock(outcome: 'granted' | 'denied', token = 'fcm-token-default') {
  let refreshListener: ((nextToken: string) => void) | null = null;
  return {
    requestPermission: jest.fn(async () => (outcome === 'granted' ? AUTHORIZED : DENIED)),
    getToken: jest.fn(async () => token),
    onTokenRefresh: jest.fn((listener: (nextToken: string) => void) => {
      refreshListener = listener;
      return jest.fn();
    }),
    __emitTokenRefresh(nextToken: string) {
      refreshListener?.(nextToken);
    },
  };
}

describe('pushRegistration', () => {
  const postDeviceToken = jest.fn();
  const deleteDeviceToken = jest.fn();

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('does not call the backend when the user denies permission', async () => {
    const messaging = requestPermissionMock('denied');

    await registerDeviceToken({ postDeviceToken, messaging });

    // A denied prompt is a normal outcome, not an error -- and must not send a token.
    expect(postDeviceToken).not.toHaveBeenCalled();
  });

  it('registers the token with its platform when permission is granted', async () => {
    const messaging = requestPermissionMock('granted', 'fcm-token-abc');

    await registerDeviceToken({ postDeviceToken, messaging });

    expect(postDeviceToken).toHaveBeenCalledWith({
      token: 'fcm-token-abc',
      platform: expect.stringMatching(/^(ANDROID|IOS)$/),
    });
  });

  it('re-registers when Firebase rotates the token', async () => {
    const messaging = requestPermissionMock('granted', 'token-1');

    await registerDeviceToken({ postDeviceToken, messaging });
    messaging.__emitTokenRefresh('token-2');

    expect(postDeviceToken).toHaveBeenLastCalledWith(
      expect.objectContaining({ token: 'token-2' }),
    );
  });

  it('never throws when the backend registration call fails', async () => {
    const messaging = requestPermissionMock('granted', 'fcm-token-abc');
    postDeviceToken.mockRejectedValueOnce(new Error('network'));

    // Failing to register a push token must never block the user from using the app.
    await expect(registerDeviceToken({ postDeviceToken, messaging })).resolves.not.toThrow();
  });

  it('revokes the token on logout', async () => {
    const messaging = requestPermissionMock('granted', 'fcm-token-abc');

    await revokeDeviceToken({ deleteDeviceToken, messaging });

    expect(deleteDeviceToken).toHaveBeenCalledWith({ token: 'fcm-token-abc' });
  });

  it('never throws when the backend revoke call fails', async () => {
    const messaging = requestPermissionMock('granted', 'fcm-token-abc');
    deleteDeviceToken.mockRejectedValueOnce(new Error('network'));

    await expect(revokeDeviceToken({ deleteDeviceToken, messaging })).resolves.not.toThrow();
  });
});
