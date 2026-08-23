import { renderHook } from '@testing-library/react-native';
import { useAuthStackInitialRoute } from './useAuthStackInitialRoute';

describe('useAuthStackInitialRoute', () => {
  it('starts on AuthEntry for a fresh session that was never signed in', () => {
    const { result } = renderHook(() => useAuthStackInitialRoute(null));

    expect(result.current).toBe('AuthEntry');
  });

  it('switches to Login once a previously-signed-in session is cleared, so a forced sign-out or explicit logout lands directly on the sign-in form rather than re-running the identify step', () => {
    // The hook's return value while token is non-null is never actually read -- the Auth stack
    // (the only consumer) isn't mounted then -- so this only asserts the state that matters: what
    // it reports once the stack is mounted again after a sign-out.
    const { result, rerender } = renderHook(({ token }: { token: string | null }) => useAuthStackInitialRoute(token), {
      initialProps: { token: 'a-real-token' },
    });

    rerender({ token: null });

    expect(result.current).toBe('Login');
  });

  it('keeps returning Login on further re-renders after the switch, not reverting back to AuthEntry', () => {
    const { result, rerender } = renderHook(({ token }: { token: string | null }) => useAuthStackInitialRoute(token), {
      initialProps: { token: 'a-real-token' },
    });
    rerender({ token: null });
    expect(result.current).toBe('Login');

    rerender({ token: null });

    expect(result.current).toBe('Login');
  });
});
