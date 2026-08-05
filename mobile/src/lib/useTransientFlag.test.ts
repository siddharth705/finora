import { act, renderHook } from '@testing-library/react-native';
import { useTransientFlag } from './useTransientFlag';

describe('useTransientFlag', () => {
  beforeEach(() => jest.useFakeTimers());
  afterEach(() => jest.useRealTimers());

  it('starts off', () => {
    const { result } = renderHook(() => useTransientFlag());
    expect(result.current[0]).toBe(false);
  });

  it('turns on when triggered and off again after the duration', () => {
    const { result } = renderHook(() => useTransientFlag(2000));

    act(() => result.current[1]());
    expect(result.current[0]).toBe(true);

    act(() => { jest.advanceTimersByTime(1999); });
    expect(result.current[0]).toBe(true);

    act(() => { jest.advanceTimersByTime(1); });
    expect(result.current[0]).toBe(false);
  });

  // Saving twice in quick succession should show one confirmation lasting the full duration from
  // the LAST save, not one that vanishes early because the first timer was still running.
  it('restarts the countdown instead of stacking timers', () => {
    const { result } = renderHook(() => useTransientFlag(2000));

    act(() => result.current[1]());
    act(() => { jest.advanceTimersByTime(1500); });
    act(() => result.current[1]());

    act(() => { jest.advanceTimersByTime(1500); });
    expect(result.current[0]).toBe(true); // the first timer would have fired by now

    act(() => { jest.advanceTimersByTime(500); });
    expect(result.current[0]).toBe(false);
  });

  /**
   * The reason this hook exists. An uncleaned timer fires into an unmounted tree and keeps the
   * runtime awake until it does.
   */
  it('clears its pending timer on unmount', () => {
    const { result, unmount } = renderHook(() => useTransientFlag(2000));

    act(() => result.current[1]());
    expect(jest.getTimerCount()).toBe(1);

    unmount();

    expect(jest.getTimerCount()).toBe(0);
  });
});
