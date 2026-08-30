import { useMemo, useState } from 'react';
import {
  ActivityIndicator, Alert, Pressable, RefreshControl, ScrollView, StyleSheet, Text,
  useWindowDimensions, View,
} from 'react-native';
import { useQueries, useQueryClient } from '@tanstack/react-query';
import { Button } from '../components/Button';
import { Card, EmptyState, SectionHeading } from '../components/Card';
import { DonutChart, type Slice } from '../components/charts/DonutChart';
import { TrendChart } from '../components/charts/TrendChart';
import { OptionPickerModal } from '../components/OptionPickerModal';
import { TextField } from '../components/TextField';
import { accountsApi, networthApi } from '../api/endpoints';
import { toUserMessage } from '../lib/apiError';
import { fmtCurrency, fmtDate } from '../lib/format';
import { useSingleFlight } from '../lib/useSingleFlight';
import { parsePositiveAmount } from '../lib/validation';
import { radius, spacing, useTheme } from '../theme';
import type { Account } from '../types';

const ALLOCATION_COLORS = ['#3b82f6', '#16a34a', '#f59e0b', '#8b5cf6', '#ef4444', '#94a3b8'];

// Same options as the web page's <select>.
const INVESTMENT_KINDS = ['Mutual Fund', 'Stocks', 'FD', 'PPF/NPS', 'Other'];

/**
 * The terms of a deposit -- what distinguishes an FD or RD from a name and a balance. Renders
 * nothing when there are no terms, which is every hand-created holding, so it only appears where it
 * actually says something. Ported from Investments.tsx's DepositTerms.
 */
function depositTerms(holding: Account): string | null {
  const terms: string[] = [];
  if (holding.principalAmount != null) terms.push(`Principal ${fmtCurrency(holding.principalAmount)}`);
  if (holding.installmentAmount != null) terms.push(`${fmtCurrency(holding.installmentAmount)}/month`);
  if (holding.installmentsPaid != null && holding.installmentsTotal != null) {
    terms.push(`${holding.installmentsPaid} of ${holding.installmentsTotal} paid`);
  }
  if (holding.interestRate != null) terms.push(`${holding.interestRate}% p.a.`);
  if (holding.maturityDate) terms.push(`Matures ${fmtDate(holding.maturityDate)}`);
  if (holding.maturityAmount != null) terms.push(`Worth ${fmtCurrency(holding.maturityAmount)} at maturity`);
  return terms.length > 0 ? terms.join(' · ') : null;
}

/** Port of frontend/src/pages/Investments.tsx. */
export function InvestmentsScreen() {
  const c = useTheme();
  const { width } = useWindowDimensions();
  const queryClient = useQueryClient();
  // See useSingleFlight: a same-frame double tap would otherwise create the holding twice, since
  // the `adding` flag that disables the button only reaches it on the next render.
  const singleFlight = useSingleFlight();

  const [name, setName] = useState('');
  const [value, setValue] = useState('');
  const [kind, setKind] = useState(INVESTMENT_KINDS[0]);
  const [kindPickerOpen, setKindPickerOpen] = useState(false);
  const [formOpen, setFormOpen] = useState(false);
  const [adding, setAdding] = useState(false);
  const [savingSnapshot, setSavingSnapshot] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // useQueries so a failing net-worth endpoint still leaves the holdings list usable, and vice
  // versa -- the web page's Promise.all loses both to either failure.
  const [accountsQ, netWorthQ] = useQueries({
    queries: [
      { queryKey: ['accounts'], queryFn: () => accountsApi.list() },
      { queryKey: ['networth'], queryFn: () => networthApi.current() },
    ],
  });

  const holdings = useMemo(
    () => (accountsQ.data ?? []).filter((a) => a.accountType === 'INVESTMENT'),
    [accountsQ.data]
  );
  const netWorth = netWorthQ.data;
  const loading = accountsQ.isLoading || netWorthQ.isLoading;
  const refreshing = (accountsQ.isFetching || netWorthQ.isFetching) && !loading;

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ['accounts'] });
    void queryClient.invalidateQueries({ queryKey: ['networth'] });
  }

  /**
   * Capped to the palette, same as DashboardScreen's donutSlices -- an uncapped one-slice-per-holding
   * mapping let the chart's reveal-in stagger (RevealArc's `delay`, one slice sweeping in after
   * another) grow without bound as holdings accumulate, and past six colours ALLOCATION_COLORS was
   * silently reused (`i % ALLOCATION_COLORS.length`), painting two different holdings the same
   * colour. Sorted by value first so the slices folded into "Other" are the smallest, least
   * individually relevant ones.
   */
  const slices: Slice[] = useMemo(() => {
    const sorted = [...holdings].sort((a, b) => b.balance - a.balance);
    if (sorted.length <= ALLOCATION_COLORS.length) {
      return sorted.map((h, i) => ({ label: h.name, value: h.balance, color: ALLOCATION_COLORS[i] }));
    }
    const named = sorted.slice(0, ALLOCATION_COLORS.length - 1);
    const rest = sorted.slice(ALLOCATION_COLORS.length - 1).reduce((sum, h) => sum + h.balance, 0);
    return [
      ...named.map((h, i) => ({ label: h.name, value: h.balance, color: ALLOCATION_COLORS[i] })),
      { label: 'Other', value: rest, color: ALLOCATION_COLORS[ALLOCATION_COLORS.length - 1] },
    ];
  }, [holdings]);
  const totalInvestments = holdings.reduce((s, h) => s + h.balance, 0);

  async function addHolding() {
    const currentValue = parsePositiveAmount(value);
    if (!name.trim()) {
      setError('Give this holding a name.');
      return;
    }
    if (currentValue === null) {
      setError('Current value must be a number greater than zero.');
      return;
    }
    setError(null);
    await singleFlight(async () => {
      setAdding(true);
      try {
        await accountsApi.create({
          name: name.trim(),
          accountType: 'INVESTMENT',
          balance: currentValue,
          investmentKind: kind,
        });
        setName(''); setValue('');
        setFormOpen(false);
        refresh();
        // A new holding changes total assets, so the Dashboard's net worth and health score move.
        void queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
      } catch (e) {
        setError(toUserMessage(e, 'Could not add this holding.'));
      } finally {
        setAdding(false);
      }
    });
  }

  function confirmDelete(h: Account) {
    Alert.alert('Delete this holding?', `"${h.name}" will be removed from your net worth.`, [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Delete', style: 'destructive', onPress: () => void removeHolding(h) },
    ]);
  }

  async function removeHolding(h: Account) {
    await singleFlight(async () => {
      setError(null);
      try {
        await accountsApi.remove(h.id);
        refresh();
        void queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
      } catch (e) {
        setError(toUserMessage(e, 'Could not delete this holding.'));
      }
    });
  }

  async function saveSnapshot() {
    // Guarded and awaited, unlike the web page's original: without the pending flag a slow response
    // let repeated taps fire concurrent snapshot writes, and without the catch a failure was an
    // unhandled rejection with no user feedback at all. The ref-based guard rather than the
    // `savingSnapshot` state, because state hasn't reached the button yet on a same-frame retap.
    await singleFlight(async () => {
      setSavingSnapshot(true);
      setError(null);
      try {
        await networthApi.saveSnapshot();
        void queryClient.invalidateQueries({ queryKey: ['networth'] });
      } catch (e) {
        setError(toUserMessage(e, 'Could not save today’s snapshot.'));
      } finally {
        setSavingSnapshot(false);
      }
    });
  }

  if (loading) {
    return (
      <View style={[styles.centered, { backgroundColor: c.bg }]}>
        <ActivityIndicator size="large" color={c.primary} />
      </View>
    );
  }

  const chartWidth = width - spacing.md * 2 - spacing.md * 2;
  const history = netWorth?.history ?? [];

  return (
    <ScrollView
      style={{ backgroundColor: c.bg }}
      contentContainerStyle={styles.content}
      keyboardShouldPersistTaps="handled"
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={refresh} tintColor={c.primary} />}
    >
      <View style={styles.header}>
        <Pressable
          onPress={() => setFormOpen((o) => !o)}
          hitSlop={12}
          accessibilityRole="button"
          accessibilityState={{ expanded: formOpen }}
        >
          <Text style={[styles.headerAction, { color: c.primary }]}>{formOpen ? 'Cancel' : '+ Add'}</Text>
        </Pressable>
      </View>

      {error ? (
        <Pressable onPress={() => setError(null)} accessibilityRole="button" accessibilityLabel="Dismiss error">
          <Text style={[styles.error, { color: c.danger, backgroundColor: c.dangerBg }]}>{error}</Text>
        </Pressable>
      ) : null}

      {formOpen ? (
        <Card style={styles.section}>
          <TextField label="Name" value={name} onChangeText={setName} placeholder="Index fund" />
          <TextField
            label="Current value"
            value={value}
            onChangeText={setValue}
            keyboardType="decimal-pad"
            placeholder="0"
          />
          <Text style={[styles.fieldLabel, { color: c.muted }]}>Type</Text>
          <Pressable
            onPress={() => setKindPickerOpen(true)}
            style={[styles.picker, { backgroundColor: c.inputBg, borderColor: c.border }]}
            accessibilityRole="button"
            accessibilityLabel={`Type: ${kind}. Change`}
          >
            <Text style={[styles.pickerText, { color: c.ink }]}>{kind}</Text>
            <Text style={[styles.chevron, { color: c.muted }]} accessibilityElementsHidden importantForAccessibility="no">
              ›
            </Text>
          </Pressable>
          <View style={styles.formButton}>
            <Button label={adding ? 'Adding…' : 'Add Holding'} onPress={() => void addHolding()} loading={adding} />
          </View>
        </Card>
      ) : null}

      <View style={styles.totals}>
        <Card style={styles.totalCard}>
          <Text style={[styles.totalLabel, { color: c.muted }]}>Investments</Text>
          <Text style={[styles.totalValue, { color: c.ink }]} numberOfLines={1} adjustsFontSizeToFit>
            {fmtCurrency(totalInvestments)}
          </Text>
        </Card>
        <Card style={styles.totalCard}>
          <Text style={[styles.totalLabel, { color: c.muted }]}>Net Worth</Text>
          <Text style={[styles.totalValue, { color: c.success }]} numberOfLines={1} adjustsFontSizeToFit>
            {netWorthQ.isError ? '—' : fmtCurrency(netWorth?.netWorth ?? 0)}
          </Text>
        </Card>
        <Card style={styles.totalCard}>
          <Text style={[styles.totalLabel, { color: c.muted }]}>Liabilities</Text>
          <Text style={[styles.totalValue, { color: c.danger }]} numberOfLines={1} adjustsFontSizeToFit>
            {netWorthQ.isError ? '—' : fmtCurrency(netWorth?.totalLiabilities ?? 0)}
          </Text>
        </Card>
      </View>

      <Card style={styles.section}>
        <SectionHeading title="Allocation" />
        {holdings.length === 0 ? (
          <EmptyState message="No investment holdings yet. Add one above, or import a deposit statement." />
        ) : (
          <DonutChart slices={slices} centerLabel={fmtCurrency(totalInvestments)} />
        )}
      </Card>

      <Card style={styles.section}>
        <SectionHeading
          title="Net Worth Trend"
          action={
            <Pressable
              onPress={() => void saveSnapshot()}
              disabled={savingSnapshot}
              style={[styles.snapshotButton, { borderColor: c.border }, savingSnapshot && styles.disabled]}
              accessibilityRole="button"
              accessibilityLabel="Save today's net worth snapshot"
              accessibilityState={{ disabled: savingSnapshot, busy: savingSnapshot }}
            >
              <Text style={[styles.snapshotText, { color: c.primary }]}>
                {savingSnapshot ? 'Saving…' : 'Save snapshot'}
              </Text>
            </Pressable>
          }
        />
        {history.length < 2 ? (
          <EmptyState message="Save a snapshot periodically to build a trend — history starts from the first one you save." />
        ) : (
          <TrendChart
            points={history.map((h) => ({ date: h.date, value: h.netWorth }))}
            width={chartWidth}
          />
        )}
      </Card>

      <Card style={styles.section}>
        <SectionHeading title="Holdings" />
        {accountsQ.isError ? (
          <Text style={[styles.inlineError, { color: c.danger }]}>Could not load holdings.</Text>
        ) : holdings.length === 0 ? (
          <EmptyState message="No holdings yet." />
        ) : (
          holdings.map((h) => {
            const terms = depositTerms(h);
            return (
              <View key={h.id} style={[styles.holdingRow, { borderBottomColor: c.border }]}>
                <View style={styles.holdingMain}>
                  <Text style={[styles.holdingName, { color: c.ink }]} numberOfLines={1}>
                    {h.name}
                  </Text>
                  {h.investmentKind ? (
                    <Text style={[styles.holdingKind, { color: c.muted }]}>{h.investmentKind}</Text>
                  ) : null}
                  {terms ? <Text style={[styles.holdingTerms, { color: c.muted }]}>{terms}</Text> : null}
                </View>
                <View style={styles.holdingRight}>
                  <Text style={[styles.holdingValue, { color: c.ink }]}>{fmtCurrency(h.balance)}</Text>
                  <Pressable
                    onPress={() => confirmDelete(h)}
                    hitSlop={8}
                    style={styles.deleteButton}
                    accessibilityRole="button"
                    accessibilityLabel={`Delete ${h.name}`}
                  >
                    <Text style={[styles.deleteText, { color: c.danger }]}>Delete</Text>
                  </Pressable>
                </View>
              </View>
            );
          })
        )}
      </Card>

      <OptionPickerModal
        visible={kindPickerOpen}
        title="Type"
        options={INVESTMENT_KINDS}
        selected={kind}
        onSelect={(k) => {
          setKind(k);
          setKindPickerOpen(false);
        }}
        onClose={() => setKindPickerOpen(false)}
      />
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  centered: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  content: { padding: spacing.md, paddingBottom: spacing.xl },
  header: { alignItems: 'flex-end', marginBottom: spacing.sm },
  headerAction: { fontSize: 14, fontWeight: '600' },
  error: {
    fontSize: 13,
    borderRadius: radius.md,
    paddingHorizontal: 12,
    paddingVertical: 10,
    marginBottom: spacing.sm,
    overflow: 'hidden',
  },
  inlineError: { fontSize: 13, paddingVertical: spacing.sm },
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
  formButton: { marginTop: spacing.sm },
  totals: { flexDirection: 'row', gap: spacing.sm },
  totalCard: { flex: 1, paddingHorizontal: spacing.sm },
  totalLabel: { fontSize: 10, textTransform: 'uppercase', letterSpacing: 0.5 },
  totalValue: { fontSize: 15, fontWeight: '700', marginTop: 4 },
  section: { marginTop: spacing.md },
  snapshotButton: {
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: 12,
    minHeight: 44,
    justifyContent: 'center',
  },
  snapshotText: { fontSize: 12, fontWeight: '600' },
  disabled: { opacity: 0.5 },
  holdingRow: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    paddingVertical: 10,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  holdingMain: { flex: 1, marginRight: spacing.sm },
  holdingName: { fontSize: 14, fontWeight: '600' },
  holdingKind: { fontSize: 10, textTransform: 'uppercase', marginTop: 2, letterSpacing: 0.4 },
  holdingTerms: { fontSize: 11, marginTop: 4, lineHeight: 16 },
  holdingRight: { alignItems: 'flex-end' },
  holdingValue: { fontSize: 14, fontWeight: '700' },
  deleteButton: { marginTop: 6, minHeight: 32, justifyContent: 'center' },
  deleteText: { fontSize: 11, fontWeight: '600', textTransform: 'uppercase' },
});
