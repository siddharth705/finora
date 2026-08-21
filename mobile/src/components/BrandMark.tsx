import Svg, { Rect } from 'react-native-svg';
import { useTheme } from '../theme';

/**
 * The Finora mark: an F cut from a graphite square rather than raised on a brand-colour one --
 * "Quiet Mark". Same geometry as the web app's BrandMark.tsx (frontend/src/components), so the
 * two platforms render the identical glyph rather than mobile keeping its own bold-font "F" in a
 * colored box (what AuthScreenLayout.tsx drew before this component existed).
 *
 * `variant="auto"` (default) reads the resolved theme's own `ink`/`bg` -- both already flip
 * correctly between light and dark, so the mark self-adjusts with zero extra logic, matching
 * every screen it sits on. `variant="fixed"` + `invert` is here for symmetry with the web
 * component, for any surface that turns out to need a mark that ignores the theme toggle.
 */
export function BrandMark({ size = 32, invert = false, variant = 'auto' }: {
  size?: number;
  invert?: boolean;
  variant?: 'fixed' | 'auto';
}) {
  const c = useTheme();
  const square = variant === 'auto' ? c.ink : invert ? '#F4F1EC' : '#262A33';
  const glyph = variant === 'auto' ? c.bg : invert ? '#262A33' : '#F4F1EC';
  return (
    <Svg width={size} height={size} viewBox="0 0 100 100">
      <Rect x={4} y={4} width={92} height={92} rx={22} fill={square} />
      <Rect x={32} y={26} width={13} height={48} rx={4} fill={glyph} />
      <Rect x={32} y={26} width={34} height={13} rx={4} fill={glyph} />
      <Rect x={32} y={50} width={25} height={12} rx={4} fill={glyph} />
    </Svg>
  );
}
