import { useEffect } from 'react';
import { Circle, Path, Polyline } from 'react-native-svg';
import Animated, {
  Easing, useAnimatedProps, useSharedValue, withDelay, withTiming,
} from 'react-native-reanimated';
import {
  DONUT_CENTER, DONUT_CIRCUMFERENCE, DONUT_RADIUS, arcLength, arcPath, type ArcSlice,
} from '../../lib/chartGeometry';

const AnimatedPath = Animated.createAnimatedComponent(Path);
const AnimatedCircle = Animated.createAnimatedComponent(Circle);
const AnimatedPolyline = Animated.createAnimatedComponent(Polyline);

/**
 * Shared timing for every progressive chart reveal in the app -- same duration and easing as
 * AnimatedNumber (src/components/AnimatedNumber.tsx), so a screen showing both (Dashboard)
 * doesn't mix two different senses of "how fast things settle." Short and monotonic: a fill-in,
 * not a bounce -- the brief's "no flashy casino-style animation" rule.
 */
export const CHART_REVEAL_DURATION = 450;
const CHART_REVEAL_EASING = Easing.out(Easing.cubic);

interface RevealArcProps {
  a: Pick<ArcSlice, 'start' | 'end' | 'full'>;
  color: string;
  strokeWidth: number;
  /** Stagger offset in ms, so slices sweep in one after another rather than all at once --
   * everything moving in lockstep is exactly the "jackpot" look the brief rules out. */
  delay?: number;
}

/**
 * One donut slice (or, when `a.full`, the single-category full ring), revealed by animating
 * strokeDashoffset from the slice's own arc length down to 0 -- a progressive draw-in scoped to
 * exactly this slice's length, so slices don't visually overlap mid-animation.
 */
export function RevealArc({ a, color, strokeWidth, delay = 0 }: RevealArcProps) {
  const length = a.full ? DONUT_CIRCUMFERENCE : arcLength(a.end - a.start);
  const progress = useSharedValue(0);

  useEffect(() => {
    progress.value = withDelay(
      delay,
      withTiming(1, { duration: CHART_REVEAL_DURATION, easing: CHART_REVEAL_EASING })
    );
    // Runs once per mount -- these charts reveal in on first render, not on every value change.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const animatedProps = useAnimatedProps(() => ({
    strokeDashoffset: length * (1 - progress.value),
  }));

  if (a.full) {
    return (
      <AnimatedCircle
        cx={DONUT_CENTER}
        cy={DONUT_CENTER}
        r={DONUT_RADIUS}
        stroke={color}
        strokeWidth={strokeWidth}
        fill="none"
        strokeDasharray={length}
        animatedProps={animatedProps}
      />
    );
  }

  return (
    <AnimatedPath
      d={arcPath(a.start, a.end)}
      stroke={color}
      strokeWidth={strokeWidth}
      fill="none"
      strokeLinecap="butt"
      strokeDasharray={length}
      animatedProps={animatedProps}
    />
  );
}

interface RevealPolylineProps {
  points: string;
  /** Precomputed via polylineLength(...) against the same {x,y} pairs used to build `points`. */
  length: number;
  color: string;
  strokeWidth: number;
  delay?: number;
}

/** Line-chart counterpart to RevealArc -- the same draw-in technique applied to a Polyline's own
 * length. Shared by CashFlowChart's two series and TrendChart's one. */
export function RevealPolyline({ points, length, color, strokeWidth, delay = 0 }: RevealPolylineProps) {
  const progress = useSharedValue(0);

  useEffect(() => {
    progress.value = withDelay(
      delay,
      withTiming(1, { duration: CHART_REVEAL_DURATION, easing: CHART_REVEAL_EASING })
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const animatedProps = useAnimatedProps(() => ({
    strokeDashoffset: length * (1 - progress.value),
  }));

  return (
    <AnimatedPolyline
      points={points}
      fill="none"
      stroke={color}
      strokeWidth={strokeWidth}
      strokeDasharray={length}
      animatedProps={animatedProps}
    />
  );
}
