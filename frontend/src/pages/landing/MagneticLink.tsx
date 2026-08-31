import { forwardRef, type CSSProperties, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { motion } from 'framer-motion';
import { useMagnetic } from './hooks/useMagnetic';

const MotionLink = motion.create(Link);
const MotionAnchor = motion.create('a');

interface MagneticLinkProps {
  to: string;
  className?: string;
  style?: CSSProperties;
  children: ReactNode;
}

/**
 * Drop-in replacement for react-router's `Link`, wrapping it in a small pointer-relative spring
 * transform (see useMagnetic) via motion.create() -- NOT a wrapping <div>, so the rendered DOM is
 * still a single <a>, and every existing className/layout at each call site is untouched. The
 * magnetic effect is a pointer-only enhancement: onPointerMove/onPointerLeave never fire from
 * keyboard interaction, so tab order, Enter/Space activation and the native :focus-visible outline
 * are exactly what Link would give you unwrapped.
 */
export const MagneticLink = forwardRef<HTMLAnchorElement, MagneticLinkProps>(function MagneticLink(
  { to, className, style, children },
  forwardedRef
) {
  const { ref, x, y, onPointerMove, onPointerLeave } = useMagnetic();
  return (
    <MotionLink
      to={to}
      className={className}
      ref={(node: HTMLAnchorElement | null) => {
        ref.current = node;
        if (typeof forwardedRef === 'function') forwardedRef(node);
        else if (forwardedRef) forwardedRef.current = node;
      }}
      style={{ ...style, x, y }}
      onPointerMove={onPointerMove}
      onPointerLeave={onPointerLeave}
    >
      {children}
    </MotionLink>
  );
});

interface MagneticAnchorProps {
  href: string;
  className?: string;
  style?: CSSProperties;
  children: ReactNode;
}

/** Same as MagneticLink but for a plain `<a href>` (e.g. in-page anchors like #how). */
export const MagneticAnchor = forwardRef<HTMLAnchorElement, MagneticAnchorProps>(function MagneticAnchor(
  { href, className, style, children },
  forwardedRef
) {
  const { ref, x, y, onPointerMove, onPointerLeave } = useMagnetic();
  return (
    <MotionAnchor
      href={href}
      className={className}
      ref={(node: HTMLAnchorElement | null) => {
        ref.current = node;
        if (typeof forwardedRef === 'function') forwardedRef(node);
        else if (forwardedRef) forwardedRef.current = node;
      }}
      style={{ ...style, x, y }}
      onPointerMove={onPointerMove}
      onPointerLeave={onPointerLeave}
    >
      {children}
    </MotionAnchor>
  );
});
