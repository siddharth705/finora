import { useWindowDimensions } from 'react-native';

/**
 * iOS's Dynamic Type "Large" size (the first standard step above the default) sits at roughly
 * 1.3x the default scale, with the accessibility sizes going well past it. Below this line,
 * single-line truncation is a reasonable space trade-off; at or above it, clipping a transaction
 * description or a goal name costs real financial information rather than saving a bit of room.
 *
 * `useWindowDimensions` (already used elsewhere in this app for width) is preferred over
 * `PixelRatio.getFontScale()` here because it's reactive -- it re-renders if the system text size
 * changes while the app is open, rather than reading a one-time snapshot at mount.
 */
const LARGE_TEXT_THRESHOLD = 1.3;

export function useLargeFontScale(): boolean {
  return useWindowDimensions().fontScale >= LARGE_TEXT_THRESHOLD;
}
