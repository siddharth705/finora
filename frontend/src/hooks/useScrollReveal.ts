import { useEffect, useRef, useState } from 'react';

/**
 * Drives the landing page's scroll-reveal animation (see the `.reveal` / `.reveal-visible`
 * classes in index.css). Fires once per element via IntersectionObserver, then disconnects —
 * a reveal is a one-time "welcome to this section" moment, not something that should replay
 * every time the user scrolls back up past it.
 */
export function useScrollReveal<T extends HTMLElement = HTMLDivElement>(threshold = 0.15) {
  const ref = useRef<T>(null);
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    const node = ref.current;
    if (!node) return;
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          setVisible(true);
          observer.disconnect();
        }
      },
      { threshold },
    );
    observer.observe(node);
    return () => observer.disconnect();
  }, [threshold]);

  return { ref, visible };
}
