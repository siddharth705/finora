import { renderHook } from '@testing-library/react';
import { createRef } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

const { revertSpy, contextSpy, timelineSpy, toSpy, registerPluginSpy } = vi.hoisted(() => {
  const revertSpy = vi.fn();
  const contextSpy = vi.fn((fn: () => void) => {
    fn();
    return { revert: revertSpy };
  });
  const toSpy = vi.fn().mockReturnThis();
  const timelineInstance = { to: toSpy };
  const timelineSpy = vi.fn((_config: { scrollTrigger: { trigger: unknown; start: string; once: boolean } }) => timelineInstance);
  const registerPluginSpy = vi.fn();
  return { revertSpy, contextSpy, timelineSpy, toSpy, registerPluginSpy };
});

vi.mock('gsap', () => ({
  gsap: {
    context: contextSpy,
    timeline: timelineSpy,
    registerPlugin: registerPluginSpy,
  },
}));
vi.mock('gsap/ScrollTrigger', () => ({ ScrollTrigger: { name: 'ScrollTrigger' } }));

import { useLearningTimeline } from './useLearningTimeline';

function makeRefs() {
  const names = [
    'containerRef', 'card1Ref', 'card2Ref', 'card3Ref', 'card4Ref',
    'connector1Ref', 'connector2Ref', 'connector3Ref',
  ] as const;
  const refs = {} as Record<(typeof names)[number], ReturnType<typeof createRef<HTMLDivElement>>>;
  for (const name of names) {
    const ref = createRef<HTMLDivElement>();
    (ref as { current: HTMLDivElement }).current = document.createElement('div');
    refs[name] = ref;
  }
  return refs;
}

describe('useLearningTimeline', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('builds a play-once timeline against the container when enabled', () => {
    const refs = makeRefs();
    renderHook(() => useLearningTimeline({ enabled: true, ...refs }));

    expect(contextSpy).toHaveBeenCalledTimes(1);
    expect(timelineSpy).toHaveBeenCalledTimes(1);
    const config = timelineSpy.mock.calls[0][0];
    expect(config.scrollTrigger.trigger).toBe(refs.containerRef.current);
    expect(config.scrollTrigger.start).toBe('top 75%');
    expect(config.scrollTrigger.once).toBe(true);
    expect(config.scrollTrigger).not.toHaveProperty('pin');
    expect(config.scrollTrigger).not.toHaveProperty('scrub');
  });

  it('animates all four cards and all three connectors', () => {
    const refs = makeRefs();
    renderHook(() => useLearningTimeline({ enabled: true, ...refs }));

    const animatedTargets = toSpy.mock.calls.map((call) => call[0]);
    expect(animatedTargets).toContain(refs.card1Ref.current);
    expect(animatedTargets).toContain(refs.card2Ref.current);
    expect(animatedTargets).toContain(refs.card3Ref.current);
    expect(animatedTargets).toContain(refs.card4Ref.current);
    expect(animatedTargets).toContain(refs.connector1Ref.current);
    expect(animatedTargets).toContain(refs.connector2Ref.current);
    expect(animatedTargets).toContain(refs.connector3Ref.current);
  });

  it('reverts the GSAP context on unmount', () => {
    const refs = makeRefs();
    const { unmount } = renderHook(() => useLearningTimeline({ enabled: true, ...refs }));
    unmount();
    expect(revertSpy).toHaveBeenCalled();
  });

  it('creates no timeline at all when disabled', () => {
    const refs = makeRefs();
    renderHook(() => useLearningTimeline({ enabled: false, ...refs }));
    expect(contextSpy).not.toHaveBeenCalled();
    expect(timelineSpy).not.toHaveBeenCalled();
  });
});
