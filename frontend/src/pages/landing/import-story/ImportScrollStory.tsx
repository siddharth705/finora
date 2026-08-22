import { useRef } from 'react';
import { useReducedMotion } from 'framer-motion';
import { useIsDesktop } from '../hooks/useIsDesktop';
import { DocumentStack } from './DocumentStack';
import { ProcessingCore } from './ProcessingCore';
import { IntelligencePanel } from './IntelligencePanel';
import { ImportRevealSequence } from './ImportRevealSequence';
import { useImportScrollTimeline } from './useImportScrollTimeline';

/**
 * Entry point for the ImportSection scroll story (see the design spec). Branches on
 * desktop-and-motion-allowed BEFORE useImportScrollTimeline is ever invoked -- disabled there is
 * a second line of defense, not the only gate. Desktop + motion allowed gets the pinned/scrubbed
 * scene; everything else (mobile, reduced-motion) gets ImportRevealSequence's reveal-once
 * version of the same three beats. The whole thing is aria-hidden -- ImportSection's own
 * eyebrow/title/blurb copy, rendered alongside this (not inside it), is what screen readers get.
 */
export function ImportScrollStory() {
  const prefersReducedMotion = useReducedMotion();
  const isDesktop = useIsDesktop();
  const usePinned = isDesktop && !prefersReducedMotion;

  const triggerRef = useRef<HTMLDivElement>(null);
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
    <div ref={triggerRef} aria-hidden="true" className="relative w-full h-[420px]">
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
