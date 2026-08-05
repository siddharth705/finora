import { StyleSheet, Text, View } from 'react-native';
import Svg, { Circle, G, Path } from 'react-native-svg';
import { fmtCurrency } from '../../lib/format';
import {
  DONUT_CENTER, DONUT_RADIUS, DONUT_SIZE, DONUT_STROKE, arcPath, buildArcs,
} from '../../lib/chartGeometry';
import { spacing, useTheme } from '../../theme';

export interface Slice {
  label: string;
  value: number;
  color: string;
}

/**
 * Replaces the web Dashboard's Chart.js <Doughnut>. Hand-rolled on react-native-svg rather than
 * pulling in a charting library: the shape needed is one ring of arcs, and every RN charting
 * package would add a native dependency to re-validate against each Expo SDK bump for it.
 *
 * The geometry lives in lib/chartGeometry.ts so its edge cases are covered by tests -- bad chart
 * math still renders, just wrongly, which neither a type-check nor a bundle would catch.
 */
export function DonutChart({ slices, centerLabel }: { slices: Slice[]; centerLabel?: string }) {
  const c = useTheme();
  const arcs = buildArcs(slices);
  // By position, not by label -- see ArcSlice.index. Looking the colour up by label gave two
  // same-named holdings the same colour and one shared React key.
  const colorFor = (arcIndex: number) => slices[arcIndex]?.color ?? c.primary;

  if (arcs.length === 0) {
    return (
      <View style={styles.wrap}>
        <Svg width={DONUT_SIZE} height={DONUT_SIZE}>
          <Circle
            cx={DONUT_CENTER}
            cy={DONUT_CENTER}
            r={DONUT_RADIUS}
            stroke={c.border}
            strokeWidth={DONUT_STROKE}
            fill="none"
          />
        </Svg>
      </View>
    );
  }

  return (
    <View style={styles.wrap}>
      <View>
        <Svg width={DONUT_SIZE} height={DONUT_SIZE}>
          <G>
            {arcs.map((a) =>
              a.full ? (
                <Circle
                  key={a.index}
                  cx={DONUT_CENTER}
                  cy={DONUT_CENTER}
                  r={DONUT_RADIUS}
                  stroke={colorFor(a.index)}
                  strokeWidth={DONUT_STROKE}
                  fill="none"
                />
              ) : (
                <Path
                  key={a.index}
                  d={arcPath(a.start, a.end)}
                  stroke={colorFor(a.index)}
                  strokeWidth={DONUT_STROKE}
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

      {/* The SVG itself is invisible to a screen reader, so this legend is the accessible
          representation of the chart, not just a colour key. */}
      <View style={styles.legend}>
        {arcs.map((a) => (
          <View key={a.index} style={styles.legendRow} accessible accessibilityLabel={`${a.label}: ${fmtCurrency(a.value)}`}>
            <View style={[styles.swatch, { backgroundColor: colorFor(a.index) }]} />
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
