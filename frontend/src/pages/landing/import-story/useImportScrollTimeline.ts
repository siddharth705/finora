import { useEffect, type RefObject } from 'react';
import { gsap } from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';

interface ImportScrollTimelineOptions {
  enabled: boolean;
  triggerRef: RefObject<HTMLElement | null>;
  stackRef: RefObject<HTMLElement | null>;
  coreRef: RefObject<HTMLElement | null>;
  panelRef: RefObject<HTMLElement | null>;
}

/**
 * The ONLY file in the ImportSection scroll story that imports gsap or gsap/ScrollTrigger --
 * see the design spec's "animation state is owned by GSAP, not React" decision. Builds one
 * timeline scrubbing DocumentStack -> ProcessingCore -> IntelligencePanel directly via their
 * root refs, entirely outside React's render cycle, so 60fps scroll updates never trigger a
 * React re-render. Beats: 0-35% scattered, 35-70% processing, 70-90% assembling, 90-100% settle
 * (glow reduces to rest before unpinning) -- see the spec for why the settle beat matters.
 *
 * Wrapped in gsap.context() specifically so the returned revert() can tear down every tween AND
 * the ScrollTrigger instance in one call on cleanup -- required for React StrictMode's dev
 * mount->unmount->remount double-invoke (the same bug class the Hero sub-project hit with Framer
 * Motion variants) and for real navigation away from the landing page.
 *
 * Pin distance is 250vh, per the spec -- computed from window.innerHeight rather than GSAP's
 * `'+=250%'` shorthand, since that shorthand means 250% of the TRIGGER element's own height, not
 * the viewport, and ImportSection's content area isn't guaranteed to be exactly one viewport tall.
 */
export function useImportScrollTimeline({ enabled, triggerRef, stackRef, coreRef, panelRef }: ImportScrollTimelineOptions): void {
  useEffect(() => {
    if (!enabled) return;
    if (!triggerRef.current || !stackRef.current || !coreRef.current || !panelRef.current) return;

    gsap.registerPlugin(ScrollTrigger);

    const ctx = gsap.context(() => {
      const tl = gsap.timeline({
        scrollTrigger: {
          trigger: triggerRef.current,
          start: 'top top',
          end: () => `+=${window.innerHeight * 2.5}`,
          pin: true,
          scrub: true,
        },
      });

      tl.to(stackRef.current, { opacity: 0, scale: 0.9, duration: 0.35 }, 0)
        .to(coreRef.current, { opacity: 1, duration: 0.35 }, 0.15)
        .to(coreRef.current, { opacity: 0, scale: 0.95, duration: 0.2 }, 0.7)
        .to(panelRef.current, { opacity: 1, y: 0, duration: 0.2 }, 0.7)
        .to(`[data-target="panel-glow"]`, { boxShadow: '0 8px 16px -8px rgba(15,23,42,.25)', duration: 0.1 }, 0.9);
    }, triggerRef);

    return () => ctx.revert();
  }, [enabled, triggerRef, stackRef, coreRef, panelRef]);
}
