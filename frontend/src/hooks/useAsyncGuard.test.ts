import { describe, it, expect } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useAsyncGuard } from './useAsyncGuard';

describe('useAsyncGuard', () => {
  it('reports the request as current when nothing newer has started', () => {
    const { result } = renderHook(() => useAsyncGuard());

    let isCurrent: () => boolean;
    act(() => {
      isCurrent = result.current.beginRequest();
    });

    expect(isCurrent!()).toBe(true);
  });

  it('reports an older request as stale once a newer one has begun -- the Reports.tsx race', () => {
    // Simulates: user switches month A -> month B before A's response arrives.
    const { result } = renderHook(() => useAsyncGuard());

    let isCurrentForMonthA: () => boolean;
    act(() => {
      isCurrentForMonthA = result.current.beginRequest();
    });

    let isCurrentForMonthB: () => boolean;
    act(() => {
      isCurrentForMonthB = result.current.beginRequest();
    });

    expect(isCurrentForMonthA!()).toBe(false);
    expect(isCurrentForMonthB!()).toBe(true);
  });

  it('reports an older request as stale once a newer one has begun -- the Merchants.tsx race', () => {
    // Simulates: user opens merchant A's audit drawer, then B's, before A's response arrives.
    const { result } = renderHook(() => useAsyncGuard());

    let isCurrentForMerchantA: () => boolean;
    function openAuditForA() {
      isCurrentForMerchantA = result.current.beginRequest();
    }
    let isCurrentForMerchantB: () => boolean;
    function openAuditForB() {
      isCurrentForMerchantB = result.current.beginRequest();
    }

    act(openAuditForA);
    act(openAuditForB);

    expect(isCurrentForMerchantA!()).toBe(false);
    expect(isCurrentForMerchantB!()).toBe(true);
  });

  it('beginRequest is referentially stable across re-renders, so it is safe as an effect dependency', () => {
    const { result, rerender } = renderHook(() => useAsyncGuard());
    const first = result.current.beginRequest;
    rerender();
    expect(result.current.beginRequest).toBe(first);
  });
});
