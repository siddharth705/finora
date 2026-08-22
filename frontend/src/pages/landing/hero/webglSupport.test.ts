import { afterEach, describe, expect, it, vi } from 'vitest';
import { isWebglAvailable } from './webglSupport';

describe('isWebglAvailable', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('returns false in jsdom, which implements no WebGL context', () => {
    // jsdom's canvas.getContext('webgl') always returns null -- this test documents that
    // environment fact and pins the function's real, unmocked behavior in this suite. Tests that
    // need the "WebGL present" branch mock this module directly (see AmbientCanvas.test.tsx).
    expect(isWebglAvailable()).toBe(false);
  });

  it('does not throw if canvas.getContext throws', () => {
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(() => {
      throw new Error('no context for you');
    });
    expect(() => isWebglAvailable()).not.toThrow();
    expect(isWebglAvailable()).toBe(false);
  });
});
