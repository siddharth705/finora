import { act, renderHook } from '@testing-library/react-native';
import { useSingleFlight } from './useSingleFlight';

/**
 * A promise whose resolution this test controls, so "still in flight" is a real state rather than
 * a timing guess. Not generic: the jest-expo babel pass reads a lone `<T>` here as JSX.
 */
function deferred(): { promise: Promise<string>; resolve: (v: string) => void } {
  let resolve: (v: string) => void = () => {};
  const promise = new Promise<string>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

describe('useSingleFlight', () => {
  it('runs the action and returns its result', async () => {
    const { result } = renderHook(() => useSingleFlight());

    let returned: string | undefined;
    await act(async () => {
      returned = await result.current(async () => 'done');
    });

    expect(returned).toBe('done');
  });

  /**
   * The bug this exists for: a `saving` state flag doesn't disable the button until the next
   * render, so two taps in the same frame both get through and the contribution is recorded twice.
   */
  it('drops a second call made before the first settles', async () => {
    const { result } = renderHook(() => useSingleFlight());
    const first = deferred();
    // Typed, so the hook's generic resolves to string rather than unknown below.
    const action = jest.fn<Promise<string>, []>()
      .mockReturnValueOnce(first.promise)
      .mockResolvedValueOnce('second');

    let firstResult: string | undefined;
    let secondResult: string | undefined;
    await act(async () => {
      // Both dispatched before either is awaited -- the same-frame double tap.
      const a = result.current(action).then((v) => { firstResult = v; });
      const b = result.current(action).then((v) => { secondResult = v; });
      first.resolve('first');
      await Promise.all([a, b]);
    });

    expect(action).toHaveBeenCalledTimes(1);
    expect(firstResult).toBe('first');
    // Dropped calls resolve to undefined rather than throwing, so callers need no extra branch.
    expect(secondResult).toBeUndefined();
  });

  it('accepts the next call once the first has settled', async () => {
    const { result } = renderHook(() => useSingleFlight());
    const action = jest.fn().mockResolvedValue('ok');

    await act(async () => {
      await result.current(action);
      await result.current(action);
    });

    expect(action).toHaveBeenCalledTimes(2);
  });

  // A failed save must not wedge the button forever -- the user has to be able to retry.
  it('releases the guard when the action rejects', async () => {
    const { result } = renderHook(() => useSingleFlight());
    const action = jest.fn()
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValueOnce('recovered');

    await act(async () => {
      await expect(result.current(action)).rejects.toThrow('network');
      await expect(result.current(action)).resolves.toBe('recovered');
    });

    expect(action).toHaveBeenCalledTimes(2);
  });
});
