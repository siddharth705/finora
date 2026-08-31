import { StyleSheet, Text, View } from 'react-native';
import Svg, { Circle, Line } from 'react-native-svg';
import { fmtCurrency, fmtDate } from '../../lib/format';
import {
  TREND_HEIGHT, TREND_PAD_TOP, TREND_PLOT_HEIGHT, polylineLength, trendScale,
} from '../../lib/chartGeometry';
import { spacing, useTheme } from '../../theme';
import { RevealPolyline } from './ChartReveal';

export interface TrendPoint {
  /** "YYYY-MM-DD" -- a LocalDate from the net worth snapshot history. */
  date: string;
  value: number;
}

/**
 * Replaces the web Investments page's Chart.js <Line> for Net Worth Trend. Single series on its own
 * range -- see DonutChart's comment for why this isn't a charting library, and lib/chartGeometry.ts
 * for the scaling and its edge cases (a negative net worth, and a flat series that would otherwise
 * divide by zero).
 */
export function TrendChart({ points, width }: { points: TrendPoint[]; width: number }) {
  const c = useTheme();

  if (points.length === 0) {
    return <Text style={[styles.empty, { color: c.muted }]}>No snapshots yet.</Text>;
  }

  const { xAt, yAt } = trendScale(points.map((p) => p.value), width);
  const polylinePoints = points.map((p, i) => `${xAt(i)},${yAt(p.value)}`).join(' ');
  const linePoints = points.map((p, i) => ({ x: xAt(i), y: yAt(p.value) }));

  const first = points[0];
  const last = points[points.length - 1];
  const change = last.value - first.value;

  return (
    <View>
      {/* The SVG is invisible to assistive tech; this carries the same information in words --
          the shape of the line is "it went up/down by this much over this period". */}
      <View
        accessible
        accessibilityLabel={`Net worth over ${points.length} snapshots, from ${fmtCurrency(
          first.value
        )} on ${fmtDate(first.date)} to ${fmtCurrency(last.value)} on ${fmtDate(last.date)}. ${
          change >= 0 ? 'Up' : 'Down'
        } ${fmtCurrency(Math.abs(change))}.`}
      >
        <Svg width={width} height={TREND_HEIGHT}>
          <Line
            x1={0}
            y1={TREND_PAD_TOP + TREND_PLOT_HEIGHT}
            x2={width}
            y2={TREND_PAD_TOP + TREND_PLOT_HEIGHT}
            stroke={c.border}
            strokeWidth={1}
          />
          <RevealPolyline
            points={polylinePoints}
            length={polylineLength(linePoints)}
            color={c.primary}
            strokeWidth={2}
          />
          {points.map((p, i) => (
            <Circle key={p.date} cx={xAt(i)} cy={yAt(p.value)} r={3} fill={c.primary} />
          ))}
        </Svg>
      </View>

      {/* First and last only. Every snapshot's date across a phone-width axis overlaps into
          unreadable mush, and the endpoints are what the line is actually read against. */}
      <View style={styles.axis} accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
        <Text style={[styles.axisLabel, { color: c.muted }]}>{fmtDate(first.date)}</Text>
        <Text style={[styles.axisLabel, { color: c.muted }]}>{fmtDate(last.date)}</Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  axis: { flexDirection: 'row', justifyContent: 'space-between', marginTop: 4 },
  axisLabel: { fontSize: 9 },
  empty: { fontSize: 13, textAlign: 'center', paddingVertical: spacing.md },
});
