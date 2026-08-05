import { useMemo, useState } from 'react';
import {
  ActivityIndicator, Pressable, RefreshControl, ScrollView, StyleSheet, Text, View,
} from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Card, EmptyState, SectionHeading } from '../components/Card';
import { OptionPickerModal } from '../components/OptionPickerModal';
import { ProgressBar } from '../components/ProgressBar';
import { reportsApi } from '../api/endpoints';
import { toUserMessage } from '../lib/apiError';
import { fmtCurrency, monthLabel } from '../lib/format';
import { shareCsv, sharePdf } from '../lib/reportExport';
import { radius, spacing, useTheme } from '../theme';

type Exporting = 'csv' | 'pdf' | null;

/**
 * Port of frontend/src/pages/Reports.tsx.
 *
 * The web page hand-rolls its month fetch with useState/useEffect and needs useAsyncGuard so a
 * slower response for a month the user already navigated away from can't overwrite the one on
 * screen. Here the report is a TanStack query keyed by month, which cannot have that bug at all --
 * an old month's response resolves into its OWN cache entry, never the current one. Same guarantee,
 * and it shares `['report', month]` with the Dashboard's cash-flow chart, so a month that screen
 * already loaded opens instantly here instead of being fetched a second time.
 */
export function ReportsScreen() {
  const c = useTheme();
  const queryClient = useQueryClient();
  // Null means "whatever the latest month is", not "none" -- see `month` below. Storing the
  // user's explicit pick rather than a resolved value is what lets the default keep tracking the
  // newest month as data arrives, without ever overriding a choice they made.
  const [pickedMonth, setPickedMonth] = useState<string | null>(null);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [exporting, setExporting] = useState<Exporting>(null);
  const [exportError, setExportError] = useState<string | null>(null);

  const {
    data: months = [], isLoading: monthsLoading, isError: monthsError,
  } = useQuery({ queryKey: ['report-months'], queryFn: () => reportsApi.availableMonths() });

  // Derived, not stored: the server returns months ascending, so the last is the most recent.
  // Writing this default into state from an effect would render once with no month and then again
  // with one, and leave two sources of truth for which month is showing.
  const month = pickedMonth ?? (months.length > 0 ? months[months.length - 1] : null);
  const monthsNewestFirst = useMemo(() => [...months].reverse(), [months]);

  const {
    data: report, isLoading: reportLoading, isError: reportError, isFetching,
  } = useQuery({
    queryKey: ['report', month],
    queryFn: () => reportsApi.forMonth(month as string),
    enabled: month !== null,
    staleTime: 5 * 60_000, // a past month's totals don't change once the month is over
  });

  async function runExport(kind: 'csv' | 'pdf') {
    if (!report || exporting) return;
    setExportError(null);
    setExporting(kind);
    try {
      await (kind === 'csv' ? shareCsv(report) : sharePdf(report));
    } catch (e) {
      setExportError(toUserMessage(e, `Could not export this report as ${kind.toUpperCase()}.`));
    } finally {
      setExporting(null);
    }
  }

  if (monthsLoading) {
    return (
      <View style={[styles.centered, { backgroundColor: c.bg }]}>
        <ActivityIndicator size="large" color={c.primary} />
      </View>
    );
  }

  if (monthsError) {
    return (
      <View style={[styles.centered, { backgroundColor: c.bg }]}>
        <Text style={[styles.message, { color: c.muted }]}>
          Couldn&apos;t load reports — please try again later.
        </Text>
      </View>
    );
  }

  if (months.length === 0) {
    return (
      <View style={[styles.centered, { backgroundColor: c.bg }]}>
        <Text style={[styles.message, { color: c.muted }]}>
          No transactions yet — import a statement to see reports.
        </Text>
      </View>
    );
  }

  const net = report ? report.income - report.expense : 0;

  return (
    <ScrollView
      style={{ backgroundColor: c.bg }}
      contentContainerStyle={styles.content}
      refreshControl={
        <RefreshControl
          refreshing={isFetching && !reportLoading}
          onRefresh={() => void queryClient.invalidateQueries({ queryKey: ['report', month] })}
          tintColor={c.primary}
        />
      }
    >
      <Card>
        <Text style={[styles.fieldLabel, { color: c.muted }]}>Month</Text>
        <Pressable
          onPress={() => setPickerOpen(true)}
          style={[styles.picker, { backgroundColor: c.inputBg, borderColor: c.border }]}
          accessibilityRole="button"
          accessibilityLabel={`Month: ${month ? monthLabel(month) : 'none'}. Change`}
        >
          <Text style={[styles.pickerText, { color: c.ink }]}>{month ? monthLabel(month) : ''}</Text>
          <Text style={[styles.chevron, { color: c.muted }]} accessibilityElementsHidden importantForAccessibility="no">
            ›
          </Text>
        </Pressable>

        <View style={styles.exportRow}>
          {(['csv', 'pdf'] as const).map((kind) => (
            <Pressable
              key={kind}
              onPress={() => void runExport(kind)}
              disabled={!report || exporting !== null}
              style={[
                styles.exportButton,
                { borderColor: c.border },
                (!report || exporting !== null) && styles.disabled,
              ]}
              accessibilityRole="button"
              accessibilityState={{ disabled: !report || exporting !== null, busy: exporting === kind }}
              accessibilityLabel={kind === 'csv' ? 'Export as CSV' : 'Export as PDF'}
            >
              <Text style={[styles.exportText, { color: c.primary }]}>
                {exporting === kind ? 'Preparing…' : kind === 'csv' ? 'Export CSV' : 'Export PDF'}
              </Text>
            </Pressable>
          ))}
        </View>
        {exportError ? <Text style={[styles.error, { color: c.danger }]}>{exportError}</Text> : null}
      </Card>

      {reportLoading ? (
        <ActivityIndicator style={styles.inlineLoader} color={c.primary} />
      ) : reportError || !report ? (
        <Card style={styles.section}>
          <Text style={[styles.error, { color: c.danger }]}>
            Couldn&apos;t load this month&apos;s report — pull down to try again.
          </Text>
        </Card>
      ) : (
        <>
          <View style={styles.totals}>
            <Card style={styles.totalCard}>
              <Text style={[styles.totalLabel, { color: c.muted }]}>Income</Text>
              <Text style={[styles.totalValue, { color: c.success }]} numberOfLines={1} adjustsFontSizeToFit>
                {fmtCurrency(report.income)}
              </Text>
            </Card>
            <Card style={styles.totalCard}>
              <Text style={[styles.totalLabel, { color: c.muted }]}>Expense</Text>
              <Text style={[styles.totalValue, { color: c.danger }]} numberOfLines={1} adjustsFontSizeToFit>
                {fmtCurrency(report.expense)}
              </Text>
            </Card>
            <Card style={styles.totalCard}>
              <Text style={[styles.totalLabel, { color: c.muted }]}>Net</Text>
              <Text
                style={[styles.totalValue, { color: net >= 0 ? c.ink : c.danger }]}
                numberOfLines={1}
                adjustsFontSizeToFit
              >
                {fmtCurrency(net)}
              </Text>
            </Card>
          </View>

          <Card style={styles.section}>
            <SectionHeading title="Category Breakdown" />
            {report.categories.length === 0 ? (
              <EmptyState message="No expenses recorded this month." />
            ) : (
              report.categories.map((cat) => {
                const pct = report.expense > 0 ? (cat.amount / report.expense) * 100 : 0;
                return (
                  <View
                    key={cat.category}
                    style={styles.categoryRow}
                    accessible
                    accessibilityLabel={`${cat.category}: ${fmtCurrency(cat.amount)}, ${pct.toFixed(
                      0
                    )} percent of this month's spending`}
                  >
                    <View style={styles.categoryHeader}>
                      <Text style={[styles.categoryName, { color: c.ink }]} numberOfLines={1}>
                        {cat.category}
                      </Text>
                      <Text style={[styles.categoryAmount, { color: c.muted }]}>{fmtCurrency(cat.amount)}</Text>
                    </View>
                    <ProgressBar pct={pct} color={c.primary} />
                  </View>
                );
              })
            )}
          </Card>
        </>
      )}

      <OptionPickerModal
        visible={pickerOpen}
        title="Month"
        // Reversed: the server sends these oldest-first, and the month someone opens this picker
        // for is nearly always a recent one. Newest at the top makes that a zero-scroll choice.
        options={monthsNewestFirst}
        selected={month}
        onSelect={(m) => {
          setPickedMonth(m);
          setPickerOpen(false);
          // Cleared with the month: an "export failed" line left over from July, sitting under
          // May's figures, reads as though May failed to export too.
          setExportError(null);
        }}
        onClose={() => setPickerOpen(false)}
      />
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  centered: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: spacing.lg },
  message: { fontSize: 14, textAlign: 'center' },
  content: { padding: spacing.md, paddingBottom: spacing.xl },
  fieldLabel: { fontSize: 12, fontWeight: '500', marginBottom: 6 },
  picker: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: 12,
    minHeight: 48,
  },
  pickerText: { fontSize: 15 },
  chevron: { fontSize: 20, lineHeight: 20 },
  exportRow: { flexDirection: 'row', gap: spacing.sm, marginTop: spacing.sm },
  exportButton: {
    flex: 1,
    borderWidth: 1,
    borderRadius: radius.md,
    minHeight: 44,
    alignItems: 'center',
    justifyContent: 'center',
  },
  exportText: { fontSize: 13, fontWeight: '600' },
  disabled: { opacity: 0.5 },
  error: { fontSize: 13, marginTop: spacing.sm },
  inlineLoader: { marginTop: spacing.lg },
  totals: { flexDirection: 'row', gap: spacing.sm, marginTop: spacing.md },
  totalCard: { flex: 1, paddingHorizontal: spacing.sm },
  totalLabel: { fontSize: 10, textTransform: 'uppercase', letterSpacing: 0.5 },
  totalValue: { fontSize: 16, fontWeight: '700', marginTop: 4 },
  section: { marginTop: spacing.md },
  categoryRow: { marginBottom: spacing.sm },
  categoryHeader: {
    flexDirection: 'row',
    alignItems: 'baseline',
    justifyContent: 'space-between',
    marginBottom: 6,
  },
  categoryName: { fontSize: 13, flex: 1, marginRight: spacing.sm },
  categoryAmount: { fontSize: 12, fontWeight: '600' },
});
