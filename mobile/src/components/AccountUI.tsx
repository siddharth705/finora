import type { ReactNode } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { radius, spacing, useTheme } from '../theme';

/**
 * Shared between ProfileScreen and SettingsScreen -- the two are explicitly two halves of what
 * used to be one page on web (Profile is "who you are", Settings is "how Finora behaves for you"),
 * and they need to stay consistent with each other rather than drift as two independent copies.
 * Ported from frontend/src/components/AccountUI.tsx.
 */

export function SectionCard({ title, subtitle, children }: {
  title: string;
  subtitle: string;
  children: ReactNode;
}) {
  const c = useTheme();
  return (
    <View style={[styles.section, { backgroundColor: c.card, borderColor: c.border }]}>
      {/* Grouped: the subtitle explains the title, and hearing them as two unrelated items loses
          that. The web version pairs each with a decorative icon; there is no room for one at
          phone width, and it carried no information anyway. */}
      <View accessible accessibilityRole="header" accessibilityLabel={`${title}. ${subtitle}`}>
        <Text style={[styles.sectionTitle, { color: c.ink }]}>{title}</Text>
        <Text style={[styles.sectionSubtitle, { color: c.muted }]}>{subtitle}</Text>
      </View>
      <View style={styles.sectionBody}>{children}</View>
    </View>
  );
}

export function VerifiedBadge() {
  const c = useTheme();
  return (
    <Text style={[styles.badge, { color: c.success, backgroundColor: c.successBg }]}>✓ Verified</Text>
  );
}

/**
 * Per-section save state: a section is either clean (nothing to show), dirty (unsaved edits),
 * mid-save, freshly saved, or errored. One indicator used identically everywhere, so "did my
 * change stick" always looks and reads the same way.
 *
 * Announces itself as a live region: the state changes without the user touching anything, and a
 * silent "Saved"/"Couldn't save" is exactly the feedback someone not looking at the screen needs.
 */
export function SaveStatus({ dirty, saving, justSaved, error }: {
  dirty: boolean;
  saving: boolean;
  justSaved: boolean;
  error: boolean;
}) {
  const c = useTheme();
  const state = error
    ? { text: "Couldn't save — please try again.", color: c.danger }
    : saving
      ? { text: 'Saving…', color: c.muted }
      : justSaved
        ? { text: '✓ Saved', color: c.success }
        : dirty
          ? { text: 'Unsaved changes', color: c.warningInk }
          : null;

  if (!state) return null;
  return (
    <Text style={[styles.saveStatus, { color: state.color }]} accessibilityLiveRegion="polite">
      {state.text}
    </Text>
  );
}

export function MetricTile({ label, value }: { label: string; value: string }) {
  const c = useTheme();
  return (
    <View style={[styles.tile, { backgroundColor: c.bg, borderColor: c.border }]} accessible accessibilityLabel={`${label}: ${value}`}>
      <Text style={[styles.tileLabel, { color: c.muted }]}>{label}</Text>
      <Text style={[styles.tileValue, { color: c.ink }]} numberOfLines={1} adjustsFontSizeToFit>
        {value}
      </Text>
    </View>
  );
}

/** A labelled fact that can't be edited here -- email, phone, member-since. */
export function ReadOnlyField({ label, value, trailing }: {
  label: string;
  value: string;
  trailing?: ReactNode;
}) {
  const c = useTheme();
  return (
    <View style={styles.readOnly}>
      <Text style={[styles.readOnlyLabel, { color: c.muted }]}>{label}</Text>
      <View style={styles.readOnlyRow}>
        <Text style={[styles.readOnlyValue, { color: c.ink }]} numberOfLines={1}>
          {value}
        </Text>
        {trailing}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  section: {
    borderWidth: 1,
    borderRadius: radius.lg,
    padding: spacing.md,
    marginBottom: spacing.md,
  },
  sectionTitle: { fontSize: 16, fontWeight: '700' },
  sectionSubtitle: { fontSize: 12, marginTop: 2 },
  sectionBody: { marginTop: spacing.md },
  badge: {
    fontSize: 11,
    fontWeight: '600',
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: radius.md,
    overflow: 'hidden',
  },
  saveStatus: { fontSize: 12 },
  tile: {
    flex: 1,
    minWidth: '45%',
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: 12,
    paddingVertical: 10,
  },
  tileLabel: { fontSize: 10, textTransform: 'uppercase', letterSpacing: 0.4 },
  tileValue: { fontSize: 16, fontWeight: '700', marginTop: 4 },
  readOnly: { marginBottom: spacing.sm },
  readOnlyLabel: { fontSize: 12, fontWeight: '500', marginBottom: 4 },
  readOnlyRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  readOnlyValue: { fontSize: 14, flexShrink: 1 },
});
