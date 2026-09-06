import { useMemo } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import Svg, { Circle, G } from 'react-native-svg';
import { fmtCurrency } from '../../lib/format';
import {
  DONUT_CENTER, DONUT_RADIUS, DONUT_SIZE, DONUT_STROKE, buildArcs,
} from '../../lib/chartGeometry';
import { useLargeFontScale } from '../../lib/useLargeFontScale';
import { spacing, useTheme } from '../../theme';
import { RevealArc } from './ChartReveal';

export interface Slice {
  label: string;
  value: number;
  color: string;
  /** Track C/C4. Optional so InvestmentsScreen's holdings donut (never drilled into) can keep
   *  passing plain {label,value,color} slices unchanged -- undefined behaves as not drillable. */
  drillable?: boolean;
}

/**
 * Replaces the web Dashboard's Chart.js <Doughnut>. Hand-rolled on react-native-svg rather than
 * pulling in a charting library: the shape needed is one ring of arcs, and every RN charting
 * package would add a native dependency to re-validate against each Expo SDK bump for it.
 *
 * The geometry lives in lib/chartGeometry.ts so its edge cases are covered by tests -- bad chart
 * math still renders, just wrongly, which neither a type-check nor a bundle would catch.
 */
export function DonutChart({
  slices,
  centerLabel,
  onSlicePress,
}: {
  slices: Slice[];
  centerLabel?: string;
  /** Track C/C4. Called with the tapped slice's own `label` -- only for a `drillable` slice;
   *  a non-drillable row (the synthetic "Other" overflow bucket) renders as plain, untappable
   *  text instead, since there is no single category a tap on it could honestly mean. */
  onSlicePress?: (label: string) => void;
}) {
  const c = useTheme();
  const largeText = useLargeFontScale();
  // Both callers already memoize `slices` specifically so a re-render doesn't redo this work --
  // that memoization only pays off if buildArcs itself doesn't run again on every render too.
  const arcs = useMemo(() => buildArcs(slices), [slices]);
  // By position, not by label -- see ArcSlice.index. Looking the colour up by label gave two
  // same-named holdings the same colour and one shared React key.
  const colorFor = (arcIndex: number) => slices[arcIndex]?.color ?? c.primary;
  // buildArcs's own return shape doesn't carry `drillable` through -- looked up the same way as
  // colorFor, by the caller's original array position.
  const drillableFor = (arcIndex: number) => Boolean(onSlicePress) && Boolean(slices[arcIndex]?.drillable);

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
            {arcs.map((a, renderIndex) => (
              <RevealArc
                key={a.index}
                a={a}
                color={colorFor(a.index)}
                strokeWidth={DONUT_STROKE}
                // Staggered by RENDER order, not `a.index` (the pre-filter position in the
                // caller's array) -- a.index skips values dropped by buildArcs's zero/negative
                // filter, so using it here left uneven gaps between slices whenever any category
                // had no spend.
                delay={renderIndex * 60}
              />
            ))}
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
        {arcs.map((a) => {
          const canDrill = drillableFor(a.index);
          const row = (
            <>
              <View style={[styles.swatch, { backgroundColor: colorFor(a.index) }]} />
              <Text style={[styles.legendLabel, { color: c.ink }]} numberOfLines={largeText ? 2 : 1}>
                {a.label}
              </Text>
              <Text style={[styles.legendValue, { color: c.mutedInk }]}>{fmtCurrency(a.value)}</Text>
            </>
          );
          // Track C/C4: a drillable row is a real navigation target, so it gets the accessible
          // role and hint a button needs -- not just a label, the same distinction StagedRowCard's
          // category chip and Dashboard's review nudge already draw for a tappable row.
          return canDrill ? (
            <Pressable
              key={a.index}
              onPress={() => onSlicePress!(a.label)}
              style={styles.legendRow}
              android_ripple={{ color: c.border }}
              accessibilityRole="button"
              accessibilityLabel={`${a.label}: ${fmtCurrency(a.value)}`}
              accessibilityHint="Opens these transactions"
            >
              {row}
            </Pressable>
          ) : (
            <View key={a.index} style={styles.legendRow} accessible accessibilityLabel={`${a.label}: ${fmtCurrency(a.value)}`}>
              {row}
            </View>
          );
        })}
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
