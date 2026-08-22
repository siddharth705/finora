import { useRef, type PointerEvent as ReactPointerEvent } from 'react';
import { useReducedMotion, useSpring } from 'framer-motion';
import { useIsDesktop } from './useIsDesktop';

const MAX_DISTANCE = 8; // px -- calm, restrained follow, not an aggressive cursor-chase
const SPRING = { stiffness: 140, damping: 20, mass: 0.3 };

/**
 * Pointer-relative spring transform for the sitewide magnetic-hover CTA effect. Gated off
 * (springs stay at 0, handlers become no-ops) under prefers-reduced-motion and on non-desktop /
 * coarse-pointer devices -- mirrors FloatingDashboardCard's own `use3D`-style gating pattern from
 * the Hero sub-project. maxDistance/stiffness/damping/mass are fixed per the global chrome spec,
 * not configurable per call site -- one calm feel across all 6 CTAs.
 */
export function useMagnetic() {
  const prefersReducedMotion = useReducedMotion();
  const isDesktop = useIsDesktop();
  const enabled = isDesktop && !prefersReducedMotion;

  const ref = useRef<HTMLElement | null>(null);
  const x = useSpring(0, SPRING);
  const y = useSpring(0, SPRING);

  function onPointerMove(event: ReactPointerEvent<HTMLElement> | PointerEvent) {
    if (!enabled) return;
    const rect = ref.current?.getBoundingClientRect();
    if (!rect) return;
    const px = (event.clientX - rect.left) / rect.width - 0.5;
    const py = (event.clientY - rect.top) / rect.height - 0.5;
    x.set(px * MAX_DISTANCE * 2);
    y.set(py * MAX_DISTANCE * 2);
  }

  function onPointerLeave() {
    x.set(0);
    y.set(0);
  }

  return { ref, x, y, onPointerMove, onPointerLeave };
}
