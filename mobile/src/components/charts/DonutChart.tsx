import { StyleSheet, Text, View } from 'react-native';
import Svg, { Circle, G, Path } from 'react-native-svg';
import { fmtCurrency } from '../../lib/format';
import { spacing, useTheme } from '../../theme';

export interface Slice {
  label: string;
  value: number;
  color: string;
}

/**
 * Replaces the web Dashboard's Chart.js <Doughnut>. Hand-rolled on react-native-svg rather than
 * pulling in a charting library: the shape needed is one ring of arcs, and every RN charting
 * package would add a native dependency to maintain against each Expo SDK bump for it.
 */
const SIZE = 160;
const STROKE = 26;
const RADIUS = (SIZE - STROKE) / 2;
const CENTER = SIZE / 2;

// SVG arcs need explicit start/end points, so polar coordinates are converted to cartesian here.
// -90° puts the first slice at 12 o'clock instead of 3 o'clock.
function pointOnCircle(angleDeg: number) {
  const rad = ((angleDeg - 90) * Math.PI) / 180;
  return { x: CENTER + RADIUS * Math.cos(rad), y: CENTER + RADIUS * Math.sin(rad) };
}

function arcPath(startAngle: number, endAngle: number): string {
  const start = pointOnCircle(startAngle);
  const end = pointOnCircle(endAngle);
  const largeArc = endAngle - startAngle > 180 ? 1 : 0;
  return `M ${start.x} ${start.y} A ${RADIUS} ${RADIUS} 0 ${largeArc} 1 ${end.x} ${end.y}`;
}

export function DonutChart({ slices, centerLabel }: { slices: Slice[]; centerLabel?: string }) {
  const c = useTheme();
  const total = slices.reduce((s, x) => s + x.value, 0);

  if (total <= 0) {
    return (
      <View style={styles.wrap}>
        <Svg width={SIZE} height={SIZE}>
          <Circle cx={CENTER} cy={CENTER} r={RADIUS} stroke={c.border} strokeWidth={STROKE} fill="none" />
        </Svg>
      </View>
    );
  }

  let cursor = 0;
  const arcs = slices
    .filter((s) => s.value > 0)
    .map((s) => {
      const sweep = (s.value / total) * 360;
      const start = cursor;
      cursor += sweep;
      // A full-circle arc can't be expressed as a single A command (start and end points would be
      // identical, which renders nothing) -- fall back to a plain circle when one slice is
      // everything, which is a real case for an account with a single spend category.
      return { ...s, start, end: cursor, full: sweep >= 359.99 };
    });

  return (
    <View style={styles.wrap}>
      <View>
        <Svg width={SIZE} height={SIZE}>
          <G>
            {arcs.map((a) =>
              a.full ? (
                <Circle key={a.label} cx={CENTER} cy={CENTER} r={RADIUS} stroke={a.color} strokeWidth={STROKE} fill="none" />
              ) : (
                <Path
                  key={a.label}
                  d={arcPath(a.start, a.end)}
                  stroke={a.color}
                  strokeWidth={STROKE}
                  fill="none"
                  strokeLinecap="butt"
                />
              )
            )}
          </G>
        </Svg>
        {centerLabel ? (
          <View style={styles.center} pointerEvents="none">
            <Text style={[styles.centerLabel, { color: c.muted }]}>Total</Text>
            <Text style={[styles.centerValue, { color: c.ink }]} numberOfLines={1}>
              {centerLabel}
            </Text>
          </View>
        ) : null}
      </View>

      <View style={styles.legend}>
        {arcs.map((a) => (
          <View key={a.label} style={styles.legendRow}>
            <View style={[styles.swatch, { backgroundColor: a.color }]} />
            <Text style={[styles.legendLabel, { color: c.ink }]} numberOfLines={1}>
              {a.label}
            </Text>
            <Text style={[styles.legendValue, { color: c.muted }]}>{fmtCurrency(a.value)}</Text>
          </View>
        ))}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: {
    alignItems: 'center',
  },
  center: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    alignItems: 'center',
    justifyContent: 'center',
  },
  centerLabel: {
    fontSize: 10,
    textTransform: 'uppercase',
    letterSpacing: 0.5,
  },
  centerValue: {
    fontSize: 15,
    fontWeight: '700',
  },
  legend: {
    alignSelf: 'stretch',
    marginTop: spacing.md,
    gap: 6,
  },
  legendRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  swatch: {
    width: 10,
    height: 10,
    borderRadius: 3,
  },
  legendLabel: {
    fontSize: 13,
    flex: 1,
  },
  legendValue: {
    fontSize: 13,
    fontWeight: '600',
  },
});
