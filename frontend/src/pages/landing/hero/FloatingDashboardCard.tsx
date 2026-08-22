import { useEffect, useRef, type PointerEvent } from 'react';
import { motion, useReducedMotion, useSpring } from 'framer-motion';
import { DashboardMock } from '../DashboardMock';
import { useIsDesktop } from '../hooks/useIsDesktop';

const TILT_RANGE = 10; // degrees of live mouse-driven tilt in either direction
const REST_ROTATE_X = 8; // initial entrance tilt, per the hero design spec
const REST_ROTATE_Y = -5;

/**
 * Wraps the real DashboardMock in a CSS-3D glass shell. The dashboard itself never enters WebGL
 * -- see the hero design spec's Non-goals -- so this is the entire "3D" effect: real DOM, a
 * perspective transform, and spring-smoothed mouse-driven tilt on top of an initial settle from
 * (8deg, -5deg) down to level.
 *
 * `use3D` gates BOTH the tilt and the richer entrance (scale+blur) behind desktop-and-motion-ok --
 * per the hero design spec's mobile fallback, touch/mobile gets the plain fade+translateY entrance
 * (matching primitives.tsx's own Reveal motion budget) and no rotateX/Y settle at all, not a
 * lighter version of the 3D one.
 */
export function FloatingDashboardCard() {
  const prefersReducedMotion = useReducedMotion();
  const isDesktop = useIsDesktop();
  const use3D = isDesktop && !prefersReducedMotion;
  const containerRef = useRef<HTMLDivElement | null>(null);

  const rotateX = useSpring(use3D ? REST_ROTATE_X : 0, { stiffness: 120, damping: 20 });
  const rotateY = useSpring(use3D ? REST_ROTATE_Y : 0, { stiffness: 120, damping: 20 });

  useEffect(() => {
    if (!use3D) return;
    rotateX.set(0);
    rotateY.set(0);
  }, [use3D, rotateX, rotateY]);

  function handlePointerMove(event: PointerEvent<HTMLDivElement>) {
    if (!use3D) return;
    const rect = containerRef.current?.getBoundingClientRect();
    if (!rect) return;
    const px = (event.clientX - rect.left) / rect.width - 0.5;
    const py = (event.clientY - rect.top) / rect.height - 0.5;
    rotateY.set(px * TILT_RANGE);
    rotateX.set(-py * TILT_RANGE);
  }

  function handlePointerLeave() {
    if (!use3D) return;
    rotateX.set(0);
    rotateY.set(0);
  }

  return (
    <motion.div
      ref={containerRef}
      onPointerMove={handlePointerMove}
      onPointerLeave={handlePointerLeave}
      initial={
        prefersReducedMotion
          ? false
          : use3D
            ? { opacity: 0, y: 80, scale: 0.95, filter: 'blur(12px)' }
            : { opacity: 0, y: 14 }
      }
      animate={use3D ? { opacity: 1, y: 0, scale: 1, filter: 'blur(0px)' } : { opacity: 1, y: 0 }}
      transition={use3D ? { duration: 0.9, ease: [0.16, 1, 0.3, 1] } : { duration: 0.42, ease: 'easeOut' }}
      style={{ rotateX, rotateY, transformPerspective: 1200, transformStyle: 'preserve-3d' }}
      className="relative"
    >
      <div className="rounded-[24px] p-1 backdrop-blur-xl bg-white/10 border border-white/15 shadow-[0_40px_100px_-32px_rgba(0,0,0,0.6)]">
        <DashboardMock level="simple" progressive />
      </div>
    </motion.div>
  );
}
