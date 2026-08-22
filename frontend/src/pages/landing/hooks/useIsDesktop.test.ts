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

  it('is true at desktop width with a fine pointer', () => {
    restore = mockMatchMedia({
      '(min-width: 768px)': true,
      '(pointer: coarse)': false,
    });
    const { result } = renderHook(() => useIsDesktop());
    expect(result.current).toBe(true);
  });

  it('is false below the desktop breakpoint', () => {
    restore = mockMatchMedia({
      '(min-width: 768px)': false,
      '(pointer: coarse)': false,
    });
    const { result } = renderHook(() => useIsDesktop());
    expect(result.current).toBe(false);
  });

  it('is false on a wide viewport with a coarse pointer (large touch tablet)', () => {
    restore = mockMatchMedia({
      '(min-width: 768px)': true,
      '(pointer: coarse)': true,
    });
    const { result } = renderHook(() => useIsDesktop());
    expect(result.current).toBe(false);
  });
});
