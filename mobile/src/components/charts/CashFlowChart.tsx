import { StyleSheet, Text, View } from 'react-native';
import Svg, { Line, Polyline, Circle } from 'react-native-svg';
import { spacing, useTheme } from '../../theme';

export interface CashFlowPoint {
  label: string;
  income: number;
  expense: number;
}

/**
 * Replaces the web Dashboard's Chart.js <Line> for Cash Flow Overview. Two series (income,
 * expense) sharing one y-scale, drawn as polylines on react-native-svg -- see DonutChart's own
 * comment for why this isn't a charting library.
 */
const HEIGHT = 150;
const PAD_TOP = 8;
const PAD_BOTTOM = 22;
const PLOT_HEIGHT = HEIGHT - PAD_TOP - PAD_BOTTOM;

export function CashFlowChart({ points, width }: { points: CashFlowPoint[]; width: number }) {
  const c = useTheme();

  if (points.length === 0) {
    return <Text style={[styles.empty, { color: c.muted }]}>No monthly data yet.</Text>;
  }

  // Both series share a scale so their heights are actually comparable -- the whole point of
  // plotting them together. Guard against an all-zero month set producing a divide-by-zero.
  const max = Math.max(1, ...points.flatMap((p) => [p.income, p.expense]));

  // A single data point has no span to divide across; place it mid-width rather than at x=NaN.
  const stepX = points.length > 1 ? width / (points.length - 1) : 0;
  const xAt = (i: number) => (points.length > 1 ? i * stepX : width / 2);
  const yAt = (v: number) => PAD_TOP + PLOT_HEIGHT - (v / max) * PLOT_HEIGHT;

  const toPolyline = (pick: (p: CashFlowPoint) => number) =>
    points.map((p, i) => `${xAt(i)},${yAt(pick(p))}`).join(' ');

  return (
    <View>
      <Svg width={width} height={HEIGHT}>
        {/* Baseline only -- a full gridline set would be noise at this size. */}
        <Line x1={0} y1={PAD_TOP + PLOT_HEIGHT} x2={width} y2={PAD_TOP + PLOT_HEIGHT} stroke={c.border} strokeWidth={1} />

        <Polyline points={toPolyline((p) => p.income)} fill="none" stroke={c.success} strokeWidth={2} />
        <Polyline points={toPolyline((p) => p.expense)} fill="none" stroke={c.danger} strokeWidth={2} />

        {points.map((p, i) => (
          <Circle key={`i-${p.label}`} cx={xAt(i)} cy={yAt(p.income)} r={3} fill={c.success} />
        ))}
        {points.map((p, i) => (
          <Circle key={`e-${p.label}`} cx={xAt(i)} cy={yAt(p.expense)} r={3} fill={c.danger} />
        ))}
      </Svg>

      <View style={[styles.axis, { width }]}>
        {points.map((p) => (
          <Text key={p.label} style={[styles.axisLabel, { color: c.muted }]} numberOfLines={1}>
            {p.label}
          </Text>
        ))}
      </View>

      <View style={styles.legend}>
        <View style={styles.legendItem}>
          <View style={[styles.swatch, { backgroundColor: c.success }]} />
          <Text style={[styles.legendText, { color: c.muted }]}>Income</Text>
        </View>
        <View style={styles.legendItem}>
          <View style={[styles.swatch, { backgroundColor: c.danger }]} />
          <Text style={[styles.legendText, { color: c.muted }]}>Expense</Text>
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  axis: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginTop: -18,
  },
  axisLabel: {
    fontSize: 9,
    flex: 1,
    textAlign: 'center',
  },
  legend: {
    flexDirection: 'row',
    gap: spacing.md,
    marginTop: spacing.sm,
    justifyContent: 'center',
  },
  legendItem: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
  },
  swatch: {
    width: 10,
    height: 3,
    borderRadius: 2,
  },
  legendText: {
    fontSize: 11,
  },
  empty: {
    fontSize: 13,
    textAlign: 'center',
    paddingVertical: spacing.md,
  },
});
