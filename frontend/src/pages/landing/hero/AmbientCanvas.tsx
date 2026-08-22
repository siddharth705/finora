import { Suspense, lazy, useState } from 'react';
import { useReducedMotion } from 'framer-motion';
import { isWebglAvailable } from './webglSupport';
import { useIsDesktop } from '../hooks/useIsDesktop';

const AmbientScene = lazy(() =>
  import('./AmbientScene').then((mod) => ({ default: mod.AmbientScene }))
);

/**
 * Ambient WebGL backdrop for the hero -- ONLY a particle/glow layer, never the dashboard itself
 * (see docs/superpowers/specs/2026-08-22-hero-cinematic-reveal-design.md's Non-goals). Gated
 * behind desktop + no-reduced-motion + real WebGL support, and code-split via React.lazy so the
 * three.js/@react-three/fiber bundle never loads when any gate fails, and never blocks the rest
 * of the hero either way.
 *
 * WebGL failing specifically (desktop, motion allowed, but no real GPU context) still gets a
 * static CSS gradient so the hero doesn't lose all ambient depth -- reduced-motion and mobile
 * render nothing at all, because the hero's own background already supplies enough surface there.
 */
export function AmbientCanvas() {
  const prefersReducedMotion = useReducedMotion();
  const isDesktop = useIsDesktop();
  const [webglOk] = useState(isWebglAvailable);

  if (prefersReducedMotion || !isDesktop) return null;

  if (!webglOk) {
    return (
      <div
        aria-hidden="true"
        data-testid="ambient-fallback-gradient"
        className="absolute inset-0 pointer-events-none"
        style={{
          background:
            'radial-gradient(60% 60% at 70% 30%, rgb(22 163 74 / .12), transparent 70%)',
        }}
      />
    );
  }

  return (
    <div className="absolute inset-0 pointer-events-none" aria-hidden="true">
      <Suspense fallback={null}>
        <AmbientScene />
      </Suspense>
    </div>
  );
}
