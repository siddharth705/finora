import { useEffect, useState } from 'react';

const DESKTOP_QUERY = '(min-width: 768px)';
const COARSE_POINTER_QUERY = '(pointer: coarse)';

function computeIsDesktop(): boolean {
  return window.matchMedia(DESKTOP_QUERY).matches && !window.matchMedia(COARSE_POINTER_QUERY).matches;
}

/** True at Tailwind's `md` breakpoint and above, AND only when the primary pointer is fine
 * (not touch) -- a wide touchscreen tablet has no meaningful hover/cursor-follow pointer even
 * though it clears the width check, so pointer-driven effects (3D tilt, magnetic buttons) must
 * stay off there too. Same breakpoint Nav.tsx already uses for its own mobile/desktop split. */
export function useIsDesktop(): boolean {
  const [isDesktop, setIsDesktop] = useState(computeIsDesktop);

  useEffect(() => {
    const desktopMql = window.matchMedia(DESKTOP_QUERY);
    const coarseMql = window.matchMedia(COARSE_POINTER_QUERY);
    const onChange = () => setIsDesktop(computeIsDesktop());
    onChange();
    desktopMql.addEventListener('change', onChange);
    coarseMql.addEventListener('change', onChange);
    return () => {
      desktopMql.removeEventListener('change', onChange);
      coarseMql.removeEventListener('change', onChange);
    };
  }, []);

  return isDesktop;
}
