import { useRef, type RefObject } from 'react';
import { useReducedMotion } from 'framer-motion';
import { useIsDesktop } from '../hooks/useIsDesktop';
import { DocumentStack } from './DocumentStack';
import { ProcessingCore } from './ProcessingCore';
import { IntelligencePanel } from './IntelligencePanel';
import { ImportRevealSequence } from './ImportRevealSequence';
import { useImportScrollTimeline } from './useImportScrollTimeline';

interface ImportScrollStoryProps {
  /**
   * The element ScrollTrigger pins -- owned by ImportSection, not this component, and deliberately
   * wraps ImportSection's ENTIRE two-column row (copy + scene), not just this scene's own box.
   *
   * Fixed a real production bug: when this scene pinned only itself, GSAP's inserted pin-spacer
   * (sized to the full ~250vh scroll distance) inflated just the scene's OWN grid column, while the
   * copy column next to it stayed its natural ~500px tall. The grid row grew to the spacer's height
   * and the copy -- vertically centered in that now-huge row via `items-center` -- ended up parked
   * at one fixed scroll position, visible for only a sliver of the ~2200px pinned scroll and blank
   * (just the floating scene, no headline) for the rest. Pinning the whole row keeps the copy
   * visually locked in place beside the scene for the entire pin, exactly as the design intended.
   */
  triggerRef: RefObject<HTMLDivElement | null>;
}

/**
 * Entry point for the ImportSection scroll story (see the design spec). Branches on
 * desktop-and-motion-allowed BEFORE useImportScrollTimeline is ever invoked -- disabled there is
 * a second line of defense, not the only gate. Desktop + motion allowed gets the pinned/scrubbed
 * scene; everything else (mobile, reduced-motion) gets ImportRevealSequence's reveal-once
 * version of the same three beats. The whole thing is aria-hidden -- ImportSection's own
 * eyebrow/title/blurb copy, rendered alongside this (not inside it), is what screen readers get.
 */
export function ImportScrollStory({ triggerRef }: ImportScrollStoryProps) {
  const prefersReducedMotion = useReducedMotion();
  const isDesktop = useIsDesktop();
  const usePinned = isDesktop && !prefersReducedMotion;

  const stackRef = useRef<HTMLDivElement>(null);
  const coreRef = useRef<HTMLDivElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);

  useImportScrollTimeline({
    enabled: usePinned,
    triggerRef,
    stackRef,
    coreRef,
    panelRef,
  });

  if (!isDesktop) {
    return <ImportRevealSequence />;
  }

  if (prefersReducedMotion) {
    return (
      <div aria-hidden="true" className="w-full h-64 flex items-center justify-center">
        <IntelligencePanel />
      </div>
    );
  }

  return (
    <div aria-hidden="true" className="relative w-full h-[420px]">
      <div className="absolute inset-0" style={{ opacity: 1 }}>
        <DocumentStack ref={stackRef} />
      </div>
      <div className="absolute inset-0 flex items-center justify-center" style={{ opacity: 0 }}>
        <ProcessingCore ref={coreRef} />
      </div>
      <div className="absolute inset-0 flex items-center justify-center" style={{ opacity: 0, transform: 'translateY(16px)' }}>
        <IntelligencePanel ref={panelRef} />
      </div>
    </div>
  );
}
