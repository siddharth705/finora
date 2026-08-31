import { StyleSheet, Text, View } from 'react-native';
import Svg, { Line, Circle } from 'react-native-svg';
import { fmtCurrency } from '../../lib/format';
import {
  CASHFLOW_HEIGHT, CASHFLOW_PAD_TOP, CASHFLOW_PLOT_HEIGHT, cashFlowScale, polylineLength, toSvgPoints,
} from '../../lib/chartGeometry';
import { spacing, useTheme } from '../../theme';
import { CHART_REVEAL_DURATION, RevealPolyline } from './ChartReveal';

export interface CashFlowPoint {
  label: string;
  income: number;
  expense: number;
}

/**
 * Replaces the web Dashboard's Chart.js <Line> for Cash Flow Overview. Two series on one shared
 * scale -- see DonutChart's comment for why this isn't a charting library, and
 * lib/chartGeometry.ts for the scaling and its edge cases.
 */
export function CashFlowChart({ points, width }: { points: CashFlowPoint[]; width: number }) {
  const c = useTheme();

  if (points.length === 0) {
    return <Text style={[styles.empty, { color: c.muted }]}>No monthly data yet.</Text>;
  }

  const { xAt, yAt } = cashFlowScale(points, width);
  // Each series' {x,y} pairs computed once, not once for the SVG points string and again for
  // polylineLength -- both are derived from this same array.
  const toSeries = (pick: (p: CashFlowPoint) => number) =>
    points.map((p, i) => ({ x: xAt(i), y: yAt(pick(p)) }));
  const incomeSeries = toSeries((p) => p.income);
  const expenseSeries = toSeries((p) => p.expense);

  return (
    <View>
      {/* The SVG can't be read by assistive tech; this label carries the same information. */}
      <View
        accessible
        accessibilityLabel={`Cash flow over ${points.length} months. ${points
          .map((p) => `${p.label}: income ${fmtCurrency(p.income)}, expense ${fmtCurrency(p.expense)}`)
          .join('. ')}`}
      >
        <Svg width={width} height={CASHFLOW_HEIGHT}>
          {/* Baseline only -- a full gridline set is noise at this size. */}
          <Line
            x1={0}
            y1={CASHFLOW_PAD_TOP + CASHFLOW_PLOT_HEIGHT}
            x2={width}
            y2={CASHFLOW_PAD_TOP + CASHFLOW_PLOT_HEIGHT}
            stroke={c.border}
            strokeWidth={1}
          />
          <RevealPolyline
            points={toSvgPoints(incomeSeries)}
            length={polylineLength(incomeSeries)}
            color={c.success}
            strokeWidth={2}
          />
          <RevealPolyline
            points={toSvgPoints(expenseSeries)}
            length={polylineLength(expenseSeries)}
            color={c.danger}
            strokeWidth={2}
            // Expense sweeps in just behind income, not simultaneously -- a small stagger reads
            // as two distinct series arriving, not one blob.
            delay={CHART_REVEAL_DURATION / 3}
          />
          {points.map((p, i) => (
            <Circle key={`i-${p.label}`} cx={xAt(i)} cy={yAt(p.income)} r={3} fill={c.success} />
          ))}
          {points.map((p, i) => (
            <Circle key={`e-${p.label}`} cx={xAt(i)} cy={yAt(p.expense)} r={3} fill={c.danger} />
          ))}
        </Svg>
      </View>

      <View style={[styles.axis, { width }]} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
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
