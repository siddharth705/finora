import { renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { mockMatchMedia } from '../../../test/mockMatchMedia';
import { useIsDesktop } from './useIsDesktop';

describe('useIsDesktop', () => {
  let restore: (() => void) | undefined;

  afterEach(() => {
    restore?.();
    restore = undefined;
  });

  it('returns true when the min-width: 768px query matches', () => {
    restore = mockMatchMedia({ '(min-width: 768px)': true });
    const { result } = renderHook(() => useIsDesktop());
    expect(result.current).toBe(true);
  });

  it('returns false when the min-width: 768px query does not match', () => {
    restore = mockMatchMedia({ '(min-width: 768px)': false });
    const { result } = renderHook(() => useIsDesktop());
    expect(result.current).toBe(false);
  });
});
