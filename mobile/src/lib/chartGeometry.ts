/**
 * Geometry for the hand-rolled SVG charts, separated from the components that draw it so the
 * degenerate cases can be tested. They're the ones that break silently: a chart with bad math
 * still renders, just wrongly, and nothing in a type-check or a bundle notices.
 */

export interface ArcSlice {
  label: string;
  value: number;
  start: number;
  end: number;
  /** A 360° arc has identical start and end points and draws nothing, so it needs a circle. */
  full: boolean;
}

export const DONUT_SIZE = 160;
export const DONUT_STROKE = 26;
export const DONUT_RADIUS = (DONUT_SIZE - DONUT_STROKE) / 2;
export const DONUT_CENTER = DONUT_SIZE / 2;

/** Polar to cartesian. -90° puts the first slice at 12 o'clock rather than 3 o'clock. */
export function pointOnCircle(angleDeg: number): { x: number; y: number } {
  const rad = ((angleDeg - 90) * Math.PI) / 180;
  return {
    x: DONUT_CENTER + DONUT_RADIUS * Math.cos(rad),
    y: DONUT_CENTER + DONUT_RADIUS * Math.sin(rad),
  };
}

export function arcPath(startAngle: number, endAngle: number): string {
  const start = pointOnCircle(startAngle);
  const end = pointOnCircle(endAngle);
  const largeArc = endAngle - startAngle > 180 ? 1 : 0;
  return `M ${start.x} ${start.y} A ${DONUT_RADIUS} ${DONUT_RADIUS} 0 ${largeArc} 1 ${end.x} ${end.y}`;
}

/**
 * Lays slices end to end around the circle. Zero and negative values are dropped rather than
 * drawn as degenerate arcs, and an empty or all-zero input yields no arcs at all so the caller
 * can render an empty ring.
 */
export function buildArcs(slices: { label: string; value: number }[]): ArcSlice[] {
  const total = slices.reduce((sum, s) => sum + Math.max(0, s.value), 0);
  if (total <= 0) return [];

  let cursor = 0;
  return slices
    .filter((s) => s.value > 0)
    .map((s) => {
      const sweep = (s.value / total) * 360;
      const start = cursor;
      cursor += sweep;
      return { label: s.label, value: s.value, start, end: cursor, full: sweep >= 359.99 };
    });
}

export const CASHFLOW_HEIGHT = 150;
export const CASHFLOW_PAD_TOP = 8;
export const CASHFLOW_PAD_BOTTOM = 22;
export const CASHFLOW_PLOT_HEIGHT = CASHFLOW_HEIGHT - CASHFLOW_PAD_TOP - CASHFLOW_PAD_BOTTOM;

export interface CashFlowScale {
  xAt: (index: number) => number;
  yAt: (value: number) => number;
  max: number;
}

/**
 * Both series share one scale so their heights are actually comparable — the whole reason for
 * plotting them together.
 *
 * Two guards that matter: `max` floors at 1 so a month set of all zeroes can't divide by zero,
 * and a single point is placed mid-width rather than at `0/0`.
 */
export function cashFlowScale(
  points: { income: number; expense: number }[],
  width: number
): CashFlowScale {
  const max = Math.max(1, ...points.flatMap((p) => [p.income, p.expense]));
  const stepX = points.length > 1 ? width / (points.length - 1) : 0;
  return {
    max,
    xAt: (i) => (points.length > 1 ? i * stepX : width / 2),
    yAt: (v) => CASHFLOW_PAD_TOP + CASHFLOW_PLOT_HEIGHT - (v / max) * CASHFLOW_PLOT_HEIGHT,
  };
}
