import { useEffect, useState } from 'react';

const QUERY = '(min-width: 768px)';

/** True at Tailwind's `md` breakpoint and above -- the same breakpoint Nav.tsx already uses for
 * its own mobile/desktop split. */
export function useIsDesktop(): boolean {
  const [isDesktop, setIsDesktop] = useState(() => window.matchMedia(QUERY).matches);

  useEffect(() => {
    const mql = window.matchMedia(QUERY);
    const onChange = () => setIsDesktop(mql.matches);
    onChange();
    mql.addEventListener('change', onChange);
    return () => mql.removeEventListener('change', onChange);
  }, []);

  return isDesktop;
}
