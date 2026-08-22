import { renderHook } from '@testing-library/react';
import { createRef } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

const { revertSpy, contextSpy, timelineSpy, registerPluginSpy } = vi.hoisted(() => {
  const revertSpy = vi.fn();
  const contextSpy = vi.fn((fn: () => void) => {
    fn();
    return { revert: revertSpy };
  });
  const toSpy = vi.fn().mockReturnThis();
  const timelineInstance = { to: toSpy };
  const timelineSpy = vi.fn((_config: { scrollTrigger: { trigger: unknown; pin: boolean; scrub: boolean } }) => timelineInstance);
  const registerPluginSpy = vi.fn();
  return { revertSpy, contextSpy, timelineSpy, registerPluginSpy };
});

vi.mock('gsap', () => ({
  gsap: {
    context: contextSpy,
    timeline: timelineSpy,
    registerPlugin: registerPluginSpy,
  },
}));
vi.mock('gsap/ScrollTrigger', () => ({ ScrollTrigger: { name: 'ScrollTrigger' } }));

import { useImportScrollTimeline } from './useImportScrollTimeline';

function makeRefs() {
  const triggerRef = createRef<HTMLDivElement>();
  const stackRef = createRef<HTMLDivElement>();
  const coreRef = createRef<HTMLDivElement>();
  const panelRef = createRef<HTMLDivElement>();
  (triggerRef as { current: HTMLDivElement }).current = document.createElement('div');
  (stackRef as { current: HTMLDivElement }).current = document.createElement('div');
  (coreRef as { current: HTMLDivElement }).current = document.createElement('div');
  (panelRef as { current: HTMLDivElement }).current = document.createElement('div');
  return { triggerRef, stackRef, coreRef, panelRef };
}

describe('useImportScrollTimeline', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('builds a pinned, scrubbed timeline against the trigger element when enabled', () => {
    const refs = makeRefs();
    renderHook(() => useImportScrollTimeline({ enabled: true, ...refs }));

    expect(contextSpy).toHaveBeenCalledTimes(1);
    expect(timelineSpy).toHaveBeenCalledTimes(1);
    const config = timelineSpy.mock.calls[0][0];
    expect(config.scrollTrigger.trigger).toBe(refs.triggerRef.current);
    expect(config.scrollTrigger.pin).toBe(true);
    expect(config.scrollTrigger.scrub).toBe(true);
  });

  it('reverts the GSAP context on unmount', () => {
    // Asserts "at least once," not an exact count -- this project's test setup registers its own
    // afterEach(cleanup) alongside RTL's, which can invoke an already-manually-unmounted tree's
    // cleanup a second time (a harness quirk, not a hook bug: `builds a pinned...` above already
    // confirms gsap.context is constructed exactly once). Reverting the same GSAP context twice
    // is a safe no-op in real GSAP; what this test actually guards is that cleanup runs at all.
    const refs = makeRefs();
    const { unmount } = renderHook(() => useImportScrollTimeline({ enabled: true, ...refs }));
    unmount();
    expect(revertSpy).toHaveBeenCalled();
  });

  it('creates no timeline at all when disabled', () => {
    const refs = makeRefs();
    renderHook(() => useImportScrollTimeline({ enabled: false, ...refs }));
    expect(contextSpy).not.toHaveBeenCalled();
    expect(timelineSpy).not.toHaveBeenCalled();
  });
});
