import { renderHook } from '@testing-library/react-native';
import { Linking } from 'react-native';
import { parseEmailChangeDeepLink, useEmailChangeDeepLink } from './useEmailChangeDeepLink';

// Spies on the real (already jest-expo-mocked) Linking module rather than replacing the whole
// 'react-native' module -- that module wires up a lot more than Linking, and re-requiring it via
// jest.requireActual bypasses jest-expo's own native-module mocking setup entirely.
const getInitialURLSpy = jest.spyOn(Linking, 'getInitialURL');
const addEventListenerSpy = jest.spyOn(Linking, 'addEventListener');

describe('parseEmailChangeDeepLink', () => {
  it('extracts sessionId and token from a well-formed link', () => {
    expect(parseEmailChangeDeepLink('finora://email-change-verify?sessionId=abc-123&token=raw-token')).toEqual({
      sessionId: 'abc-123', token: 'raw-token',
    });
  });

  it('decodes URL-encoded param values', () => {
    expect(parseEmailChangeDeepLink('finora://email-change-verify?sessionId=abc%2F123&token=a%26b')).toEqual({
      sessionId: 'abc/123', token: 'a&b',
    });
  });

  it('returns null for a different path this app does not handle', () => {
    expect(parseEmailChangeDeepLink('finora://some-other-path?sessionId=abc&token=xyz')).toBeNull();
  });

  it('returns null when sessionId is missing', () => {
    expect(parseEmailChangeDeepLink('finora://email-change-verify?token=xyz')).toBeNull();
  });

  it('returns null when token is missing', () => {
    expect(parseEmailChangeDeepLink('finora://email-change-verify?sessionId=abc')).toBeNull();
  });

  it('returns null for a completely unrelated URL, without throwing', () => {
    expect(parseEmailChangeDeepLink('https://example.com/whatever')).toBeNull();
  });
});

describe('useEmailChangeDeepLink', () => {
  let urlListener: ((event: { url: string }) => void) | null;

  function fakeNavigationRef(overrides: Partial<{ isReady: () => boolean }> = {}) {
    return {
      current: {},
      isReady: overrides.isReady ?? (() => true),
      navigate: jest.fn(),
    } as unknown as Parameters<typeof useEmailChangeDeepLink>[0];
  }

  beforeEach(() => {
    urlListener = null;
    getInitialURLSpy.mockReset().mockResolvedValue(null);
    addEventListenerSpy.mockReset().mockImplementation((_event, listener) => {
      urlListener = listener as (event: { url: string }) => void;
      return { remove: jest.fn() } as never;
    });
  });

  it('navigates immediately when a deep link arrives while already signed in and ready', async () => {
    const navigationRef = fakeNavigationRef();
    renderHook(() => useEmailChangeDeepLink(navigationRef, true));
    await Promise.resolve();

    urlListener?.({ url: 'finora://email-change-verify?sessionId=s1&token=t1' });

    expect(navigationRef.navigate).toHaveBeenCalledWith('More', {
      screen: 'VerifyEmailChange', params: { sessionId: 's1', token: 't1' },
    });
  });

  it('stashes the link and does not navigate yet when the app is not ready (e.g. signed out)', async () => {
    const navigationRef = fakeNavigationRef();
    renderHook(() => useEmailChangeDeepLink(navigationRef, false));
    await Promise.resolve();

    urlListener?.({ url: 'finora://email-change-verify?sessionId=s1&token=t1' });

    expect(navigationRef.navigate).not.toHaveBeenCalled();
  });

  it('replays the stashed link once the app becomes ready (signs in after tapping the link while signed out)', async () => {
    const navigationRef = fakeNavigationRef();
    const { rerender } = renderHook(({ ready }: { ready: boolean }) => useEmailChangeDeepLink(navigationRef, ready), {
      initialProps: { ready: false },
    });
    await Promise.resolve();
    urlListener?.({ url: 'finora://email-change-verify?sessionId=s1&token=t1' });
    expect(navigationRef.navigate).not.toHaveBeenCalled();

    rerender({ ready: true });

    expect(navigationRef.navigate).toHaveBeenCalledWith('More', {
      screen: 'VerifyEmailChange', params: { sessionId: 's1', token: 't1' },
    });
  });

  it('only replays once -- a second ready transition does not re-navigate with a stale link', async () => {
    const navigationRef = fakeNavigationRef();
    const { rerender } = renderHook(({ ready }: { ready: boolean }) => useEmailChangeDeepLink(navigationRef, ready), {
      initialProps: { ready: false },
    });
    await Promise.resolve();
    urlListener?.({ url: 'finora://email-change-verify?sessionId=s1&token=t1' });
    rerender({ ready: true });
    expect(navigationRef.navigate).toHaveBeenCalledTimes(1);

    rerender({ ready: false });
    rerender({ ready: true });

    expect(navigationRef.navigate).toHaveBeenCalledTimes(1);
  });

  it('picks up a cold-launch link from getInitialURL, not just the live "url" event', async () => {
    getInitialURLSpy.mockResolvedValue('finora://email-change-verify?sessionId=cold&token=launch');
    const navigationRef = fakeNavigationRef();
    renderHook(() => useEmailChangeDeepLink(navigationRef, true));
    await Promise.resolve();
    await Promise.resolve();

    expect(navigationRef.navigate).toHaveBeenCalledWith('More', {
      screen: 'VerifyEmailChange', params: { sessionId: 'cold', token: 'launch' },
    });
  });

  it('ignores a URL that does not match the email-change-verify path', async () => {
    const navigationRef = fakeNavigationRef();
    renderHook(() => useEmailChangeDeepLink(navigationRef, true));
    await Promise.resolve();

    urlListener?.({ url: 'finora://some-other-screen' });

    expect(navigationRef.navigate).not.toHaveBeenCalled();
  });

  it('calls onNavigationReady (wired to NavigationContainer.onReady) to retry a pending link once the ref actually reports ready', async () => {
    let readyNow = false;
    const navigationRef = fakeNavigationRef({ isReady: () => readyNow });
    const { result } = renderHook(() => useEmailChangeDeepLink(navigationRef, true));
    await Promise.resolve();
    urlListener?.({ url: 'finora://email-change-verify?sessionId=s1&token=t1' });
    expect(navigationRef.navigate).not.toHaveBeenCalled();

    readyNow = true;
    result.current.onNavigationReady();

    expect(navigationRef.navigate).toHaveBeenCalledWith('More', {
      screen: 'VerifyEmailChange', params: { sessionId: 's1', token: 't1' },
    });
  });
});
