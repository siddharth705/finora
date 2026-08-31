import { useEffect, type RefObject } from 'react';
import { gsap } from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';

interface LearningTimelineOptions {
  enabled: boolean;
  containerRef: RefObject<HTMLElement | null>;
  card1Ref: RefObject<HTMLElement | null>;
  card2Ref: RefObject<HTMLElement | null>;
  card3Ref: RefObject<HTMLElement | null>;
  card4Ref: RefObject<HTMLElement | null>;
  connector1Ref: RefObject<HTMLElement | null>;
  connector2Ref: RefObject<HTMLElement | null>;
  connector3Ref: RefObject<HTMLElement | null>;
}

/**
 * The only file in the LearningSection reinforcement sequence that imports gsap or
 * gsap/ScrollTrigger -- mirrors useImportScrollTimeline's "animation state lives in GSAP via
 * refs, not React state" rule. Unlike ImportSection's pinned/scrubbed scene, this is a
 * play-once, never-reverse sequence: `once: true` auto-kills the ScrollTrigger after the first
 * hit, which is "enter viewport -> play -> stay played" -- see the design spec's "Trigger"
 * section for why this isn't toggleActions or a pin. No `pin`, no `scrub` in the scrollTrigger
 * config, deliberately -- their absence is asserted directly in the test above.
 *
 * Named individual refs rather than an array/object of refs: an array literal is a new object
 * identity every render, which would re-run this effect on every render if it were a dependency.
 *
 * Eases use GSAP's built-in named curves (power3.out / power2.inOut) rather than an exact
 * cubic-bezier match to the rest of the page's [0.16,1,0.3,1] curve -- matching it precisely
 * would need the CustomEase plugin for one cosmetic detail, which isn't worth the extra import.
 */
export function useLearningTimeline({
  enabled,
  containerRef,
  card1Ref,
  card2Ref,
  card3Ref,
  card4Ref,
  connector1Ref,
  connector2Ref,
  connector3Ref,
}: LearningTimelineOptions): void {
  useEffect(() => {
    if (!enabled) return;
    const cardRefs = [card1Ref, card2Ref, card3Ref, card4Ref];
    const connectorRefs = [connector1Ref, connector2Ref, connector3Ref];
    if (!containerRef.current) return;
    if (cardRefs.some((r) => !r.current) || connectorRefs.some((r) => !r.current)) return;

    gsap.registerPlugin(ScrollTrigger);

    const ctx = gsap.context(() => {
      const tl = gsap.timeline({
        scrollTrigger: {
          trigger: containerRef.current,
          start: 'top 75%',
          once: true,
        },
      });

      cardRefs.forEach((cardRef, i) => {
        const at = i * 0.6;
        tl.to(cardRef.current, { opacity: 1, y: 0, duration: 0.5, ease: 'power3.out' }, at);
        const connectorRef = connectorRefs[i];
        if (connectorRef) {
          tl.to(connectorRef.current, { scaleX: 1, duration: 0.4, ease: 'power2.inOut' }, at + 0.3);
        }
      });

      tl.to(
        '[data-target="confirmation"]',
        { scale: 1.06, duration: 0.2, ease: 'power2.out', yoyo: true, repeat: 1 },
        '>-0.1'
      );
    }, containerRef);

    return () => ctx.revert();
  }, [enabled, containerRef, card1Ref, card2Ref, card3Ref, card4Ref, connector1Ref, connector2Ref, connector3Ref]);
}
