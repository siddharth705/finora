import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useDelayedLoading } from './useDelayedLoading';

describe('useDelayedLoading', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('stays hidden for a fetch that resolves inside the showAfter window', () => {
    const { result, rerender } = renderHook(({ isLoading }) => useDelayedLoading(isLoading, { showAfter: 200, minVisible: 300 }), {
      initialProps: { isLoading: true },
    });

    expect(result.current).toBe(false);

    act(() => {
      vi.advanceTimersByTime(150);
    });
    rerender({ isLoading: false });

    act(() => {
      vi.advanceTimersByTime(500);
    });
    expect(result.current).toBe(false);
  });

  it('appears once the fetch survives past showAfter', () => {
    const { result, rerender } = renderHook(({ isLoading }) => useDelayedLoading(isLoading, { showAfter: 200, minVisible: 300 }), {
      initialProps: { isLoading: true },
    });

    act(() => {
      vi.advanceTimersByTime(200);
    });
    rerender({ isLoading: true });

    expect(result.current).toBe(true);
  });

  it('stays visible for at least minVisible even if the fetch finishes right after showing', () => {
    const { result, rerender } = renderHook(({ isLoading }) => useDelayedLoading(isLoading, { showAfter: 200, minVisible: 300 }), {
      initialProps: { isLoading: true },
    });

    act(() => {
      vi.advanceTimersByTime(200);
    });
    rerender({ isLoading: true });
    expect(result.current).toBe(true);

    rerender({ isLoading: false });
    act(() => {
      vi.advanceTimersByTime(100);
    });
    expect(result.current).toBe(true);

    act(() => {
      vi.advanceTimersByTime(250);
    });
    expect(result.current).toBe(false);
  });

  it('does not hide on a stale timer if loading restarts before the minVisible window closes', () => {
    const { result, rerender } = renderHook(({ isLoading }) => useDelayedLoading(isLoading, { showAfter: 200, minVisible: 300 }), {
      initialProps: { isLoading: true },
    });

    act(() => {
      vi.advanceTimersByTime(200);
    });
    rerender({ isLoading: true });
    expect(result.current).toBe(true);

    rerender({ isLoading: false });
    act(() => {
      vi.advanceTimersByTime(50);
    });
    rerender({ isLoading: true });

    act(() => {
      vi.advanceTimersByTime(300);
    });
    expect(result.current).toBe(true);
  });

  it('does not extend minVisible when a flicker re-enters the show branch without ever actually hiding', () => {
    // Regression test: shown at t=200. Flickers false at t=210 (before the hide timer, still
    // pending from minVisible, has a chance to fire) then true again at t=220 -- the skeleton was
    // never actually taken off screen, so the second show-timer firing at t=420 must NOT reset
    // the "shown at" clock. minVisible is measured from the ORIGINAL t=200 show throughout.
    const { result, rerender } = renderHook(({ isLoading }) => useDelayedLoading(isLoading, { showAfter: 200, minVisible: 300 }), {
      initialProps: { isLoading: true },
    });

    act(() => {
      vi.advanceTimersByTime(200);
    });
    rerender({ isLoading: true }); // shown at t=200
    expect(result.current).toBe(true);

    rerender({ isLoading: false }); // t=200, flicker starts
    act(() => {
      vi.advanceTimersByTime(10);
    });
    rerender({ isLoading: true }); // t=210, flicker ends before the pending hide timer could fire

    act(() => {
      vi.advanceTimersByTime(200);
    });
    rerender({ isLoading: true }); // t=410, second show-timer fires -- must not restamp shownAtRef
    expect(result.current).toBe(true);

    rerender({ isLoading: false }); // t=410, genuinely done now
    act(() => {
      vi.advanceTimersByTime(85);
    });
    // t=495: 295ms since the ORIGINAL t=200 show -- minVisible (300ms) hasn't elapsed yet
    expect(result.current).toBe(true);

    act(() => {
      vi.advanceTimersByTime(10);
    });
    // t=505: 305ms since t=200 -- now it should hide. If the bug were present, shownAtRef would
    // have been stomped to 410 and this would still show (only 95ms since the stomped timestamp).
    expect(result.current).toBe(false);
  });

  it('uses the documented defaults (200ms / 300ms) when no options are passed', () => {
    const { result, rerender } = renderHook(({ isLoading }) => useDelayedLoading(isLoading), {
      initialProps: { isLoading: true },
    });

    act(() => {
      vi.advanceTimersByTime(199);
    });
    rerender({ isLoading: true });
    expect(result.current).toBe(false);

    act(() => {
      vi.advanceTimersByTime(1);
    });
    rerender({ isLoading: true });
    expect(result.current).toBe(true);
  });
});
