/**
 * Geometry for the hand-rolled SVG charts, separated from the components that draw it so the
 * degenerate cases can be tested. They're the ones that break silently: a chart with bad math
 * still renders, just wrongly, and nothing in a type-check or a bundle notices.
 */

export interface ArcSlice {
  /**
   * Position in the ORIGINAL input array, which survives the zero/negative filtering below.
   *
   * The identity of a slice, deliberately not its label: labels are unique on the Dashboard (spend
   * categories) but not on Investments, where they're holding names the user typed and two
   * holdings may genuinely share one. Keying or colouring by label there collapses both into one
   * React key and paints them the same colour.
   */
  index: number;
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
  // Indexed BEFORE filtering, so `index` still points at the caller's own array.
  return slices
    .map((s, index) => ({ ...s, index }))
    .filter((s) => s.value > 0)
    .map((s) => {
      const sweep = (s.value / total) * 360;
      const start = cursor;
      cursor += sweep;
      return { index: s.index, label: s.label, value: s.value, start, end: cursor, full: sweep >= 359.99 };
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

export const TREND_HEIGHT = 150;
export const TREND_PAD_TOP = 8;
export const TREND_PAD_BOTTOM = 22;
export const TREND_PLOT_HEIGHT = TREND_HEIGHT - TREND_PAD_TOP - TREND_PAD_BOTTOM;

export interface TrendScale {
  xAt: (index: number) => number;
  yAt: (value: number) => number;
  min: number;
  max: number;
}

/**
 * Single-series counterpart to cashFlowScale, used by the net worth trend line. Income/expense are
 * always >= 0, so cashFlowScale anchors at zero; net worth can go negative (liabilities exceeding
 * assets) and the web Chart.js line auto-fits to the data's own range rather than forcing zero into
 * frame, so this scales to [dataMin, dataMax] to match -- pinning at zero would flatten a trend
 * that never approaches zero into a nearly flat line hugging one edge.
 */
export function trendScale(values: number[], width: number): TrendScale {
  if (values.length === 0) {
    return { min: 0, max: 1, xAt: () => width / 2, yAt: () => TREND_PAD_TOP + TREND_PLOT_HEIGHT };
  }

  const dataMin = Math.min(...values);
  const dataMax = Math.max(...values);
  // A flat or single-value series has dataMin === dataMax, which would divide by zero below.
  // A synthetic half-unit span centers the flat line in the plot instead of producing NaN.
  const min = dataMin === dataMax ? dataMin - 0.5 : dataMin;
  const max = dataMin === dataMax ? dataMax + 0.5 : dataMax;
  const stepX = values.length > 1 ? width / (values.length - 1) : 0;
  return {
    min,
    max,
    xAt: (i) => (values.length > 1 ? i * stepX : width / 2),
    yAt: (v) => TREND_PAD_TOP + TREND_PLOT_HEIGHT - ((v - min) / (max - min)) * TREND_PLOT_HEIGHT,
  };
}
