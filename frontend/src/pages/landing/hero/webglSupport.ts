/**
 * Detects real WebGL support by actually creating a context, rather than trusting the UA string
 * -- some browsers report support they can't deliver (GPU blocklisted, disabled by policy), and
 * creating the context is the only reliable way to find out before React Three Fiber tries and
 * throws mid-render.
 */
export function isWebglAvailable(): boolean {
  try {
    const canvas = document.createElement('canvas');
    return !!(canvas.getContext('webgl2') || canvas.getContext('webgl'));
  } catch {
    return false;
  }
}
