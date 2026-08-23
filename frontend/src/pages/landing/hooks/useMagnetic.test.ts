import { renderHook, act } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { mockMatchMedia } from '../../../test/mockMatchMedia';

vi.mock('framer-motion', async (importOriginal) => {
  const actual = await importOriginal<typeof import('framer-motion')>();
  return { ...actual, useReducedMotion: vi.fn() };
});

import { useReducedMotion } from 'framer-motion';
import { useMagnetic } from './useMagnetic';

describe('useMagnetic', () => {
  let restore: (() => void) | undefined;

  afterEach(() => {
    restore?.();
    restore = undefined;
    vi.clearAllMocks();
  });

  it('exposes zeroed x/y springs and a ref before any pointer movement', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    restore = mockMatchMedia({ '(min-width: 768px)': true, '(pointer: coarse)': false });
    const { result } = renderHook(() => useMagnetic());
    expect(result.current.x.get()).toBe(0);
    expect(result.current.y.get()).toBe(0);
  });

  it('does not move the springs on pointer move under prefers-reduced-motion', () => {
    vi.mocked(useReducedMotion).mockReturnValue(true);
    restore = mockMatchMedia({ '(min-width: 768px)': true, '(pointer: coarse)': false });
    const { result } = renderHook(() => useMagnetic());
    const el = document.createElement('div');
    Object.assign(result.current.ref, { current: el });
    vi.spyOn(el, 'getBoundingClientRect').mockReturnValue({
      left: 0, top: 0, width: 100, height: 40, right: 100, bottom: 40, x: 0, y: 0, toJSON: () => {},
    } as DOMRect);
    act(() => {
      result.current.onPointerMove({ clientX: 90, clientY: 36 } as unknown as PointerEvent);
    });
    expect(result.current.x.get()).toBe(0);
    expect(result.current.y.get()).toBe(0);
  });

  it('does not move the springs below the desktop breakpoint', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    restore = mockMatchMedia({ '(min-width: 768px)': false, '(pointer: coarse)': false });
    const { result } = renderHook(() => useMagnetic());
    const el = document.createElement('div');
    Object.assign(result.current.ref, { current: el });
    vi.spyOn(el, 'getBoundingClientRect').mockReturnValue({
      left: 0, top: 0, width: 100, height: 40, right: 100, bottom: 40, x: 0, y: 0, toJSON: () => {},
    } as DOMRect);
    act(() => {
      result.current.onPointerMove({ clientX: 90, clientY: 36 } as unknown as PointerEvent);
    });
    expect(result.current.x.get()).toBe(0);
    expect(result.current.y.get()).toBe(0);
  });

  it('resets the springs to 0 on pointer leave', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    restore = mockMatchMedia({ '(min-width: 768px)': true, '(pointer: coarse)': false });
    const { result } = renderHook(() => useMagnetic());
    act(() => {
      result.current.x.set(4);
      result.current.y.set(-3);
      result.current.onPointerLeave();
    });
    expect(result.current.x.get()).toBe(0);
    expect(result.current.y.get()).toBe(0);
  });
});
