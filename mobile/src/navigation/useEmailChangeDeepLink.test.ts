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

  // D6 (Track D security cleanup). The scenario the roadmap names: user A taps the link before
  // ready, then signs out (or abandons) before the navigator ever actually became ready to
  // consume it -- the exact race onNavigationReady exists for, caught here mid-flight by a
  // sign-out. Without clearing pendingRef on that transition, whoever signs in next on this
  // device would have A's stale link replayed the moment THEIR session becomes ready.
  it('clears a still-pending link on sign-out, so a later sign-in never replays a stale identity\'s link', async () => {
    let readyNow = false; // navigationRef.isReady() itself, independent of the hook's `ready` prop
    const navigationRef = fakeNavigationRef({ isReady: () => readyNow });
    const { rerender } = renderHook(({ ready }: { ready: boolean }) => useEmailChangeDeepLink(navigationRef, ready), {
      initialProps: { ready: false },
    });
    await Promise.resolve();
    urlListener?.({ url: 'finora://email-change-verify?sessionId=s1&token=t1' });

    // `ready` flips true, but the navigator itself isn't ready yet -- the link is still sitting
    // in pendingRef, unconsumed.
    rerender({ ready: true });
    expect(navigationRef.navigate).not.toHaveBeenCalled();

    // User A signs out before the navigator ever became ready.
    rerender({ ready: false });

    // A different user signs in on this device, and this time the navigator IS ready.
    readyNow = true;
    rerender({ ready: true });

    expect(navigationRef.navigate).not.toHaveBeenCalled();
  });

  it('does NOT clear a pending link across the initial not-ready -> ready transition (the ordinary sign-in-after-tapping-the-link flow)', async () => {
    const navigationRef = fakeNavigationRef();
    const { rerender } = renderHook(({ ready }: { ready: boolean }) => useEmailChangeDeepLink(navigationRef, ready), {
      initialProps: { ready: false },
    });
    await Promise.resolve();
    urlListener?.({ url: 'finora://email-change-verify?sessionId=s1&token=t1' });

    rerender({ ready: true });

    // Confirms the sign-out guard is keyed on a TRUE -> false transition specifically, not on
    // `!ready` ever having been observed -- otherwise this would regress the ordinary "tapped the
    // link while signed out, then signed in" flow the earlier test in this file already covers.
    expect(navigationRef.navigate).toHaveBeenCalledWith('More', {
      screen: 'VerifyEmailChange', params: { sessionId: 's1', token: 't1' },
    });
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
