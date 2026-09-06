import { useMemo, useState } from 'react';
import {
  ActivityIndicator, Alert, Pressable, RefreshControl, ScrollView, StyleSheet, Text,
  useWindowDimensions, View,
} from 'react-native';
import { useQueries, useQueryClient } from '@tanstack/react-query';
import { usePreventScreenCapture } from 'expo-screen-capture';
import { Button } from '../components/Button';
import { Card, EmptyState, SectionHeading } from '../components/Card';
import { DonutChart, type Slice } from '../components/charts/DonutChart';
import { TrendChart } from '../components/charts/TrendChart';
import { OptionPickerModal } from '../components/OptionPickerModal';
import { TextField } from '../components/TextField';
import { accountsApi, networthApi } from '../api/endpoints';
import { toUserMessage } from '../lib/apiError';
import { CHART_PALETTE, bucketTopSlices } from '../lib/chartGeometry';
import { fmtCurrency, fmtDate } from '../lib/format';
import { isPausedCold } from '../lib/refreshingIndicator';
import { useSingleFlight } from '../lib/useSingleFlight';
import { parsePositiveAmount } from '../lib/validation';
import { radius, spacing, useTheme } from '../theme';
import type { Account } from '../types';

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
  // D3 (Track D security cleanup). Holdings and net worth are the single most sensitive figures
  // in the app -- same screenshot/screen-recording exposure Dashboard/Accounts/Statement History
  // already guard against.
  usePreventScreenCapture();
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
  // A cold query paused for lack of connectivity is neither an error nor an answer -- see
  // isPausedCold. Folded in alongside isError everywhere a figure or an empty state would
  // otherwise be stated as fact, so going offline reads as "not available" rather than as ₹0.
  const accountsUnknown = accountsQ.isError || isPausedCold(accountsQ);
  const netWorthUnknown = netWorthQ.isError || isPausedCold(netWorthQ);
  const refreshing = (accountsQ.isFetching || netWorthQ.isFetching) && !loading;

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ['accounts'] });
    void queryClient.invalidateQueries({ queryKey: ['networth'] });
  }

  /**
   * Capped to the palette, same as DashboardScreen's donutSlices (both call the shared
   * bucketTopSlices) -- an uncapped one-slice-per-holding mapping let the chart's reveal-in
   * stagger (RevealArc's `delay`, one slice sweeping in after another) grow without bound as
   * holdings accumulate, and past six colours the palette was silently reused, painting two
   * different holdings the same colour. bucketTopSlices also merges the overflow into a holding a
   * user has literally named "Other" rather than rendering two identically-labelled legend rows --
   * holding names are free text, and "Other" is a plausible one to type.
   */
  const slices: Slice[] = useMemo(
    () => bucketTopSlices(holdings.map((h) => [h.name, h.balance]), CHART_PALETTE, 'Other'),
    [holdings]
  );
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
            {/* '—' on failure, matching the two cards beside it. totalInvestments is derived from
                `holdings`, which is [] when the accounts fetch failed -- so this used to state a
                confident ₹0 for a portfolio it had simply been unable to load, sitting directly
                next to two cards that correctly admitted they didn't know. A fabricated zero is
                the worst of the three outcomes here: it is indistinguishable from a real answer. */}
            {accountsUnknown ? '—' : fmtCurrency(totalInvestments)}
          </Text>
        </Card>
        <Card style={styles.totalCard}>
          <Text style={[styles.totalLabel, { color: c.muted }]}>Net Worth</Text>
          {/* Sign-aware, like ReportsScreen's own Net card. Net worth is a genuinely signed figure
              -- AccountBalanceConvention treats a credit card as a liability, so anyone who imports
              a card statement before a bank one is legitimately negative -- and painting that green
              inverts the only signal the colour carries. */}
          <Text
            style={[
              styles.totalValue,
              { color: (netWorth?.netWorth ?? 0) >= 0 ? c.success : c.danger },
            ]}
            numberOfLines={1}
            adjustsFontSizeToFit
          >
            {netWorthUnknown ? '—' : fmtCurrency(netWorth?.netWorth ?? 0)}
          </Text>
        </Card>
        <Card style={styles.totalCard}>
          <Text style={[styles.totalLabel, { color: c.muted }]}>Liabilities</Text>
          <Text style={[styles.totalValue, { color: c.danger }]} numberOfLines={1} adjustsFontSizeToFit>
            {netWorthUnknown ? '—' : fmtCurrency(netWorth?.totalLiabilities ?? 0)}
          </Text>
        </Card>
      </View>

      <Card style={styles.section}>
        <SectionHeading title="Allocation" />
        {/* The isError branch has to come first, for the same reason the Holdings card below states
            it: `holdings` is [] both when the user genuinely has none and when the fetch failed,
            and only one of those is an answer. Without this, one failed /accounts told the user
            "No investment holdings yet" here and "Could not load holdings." forty lines down. */}
        {accountsUnknown ? (
          <Text style={[styles.inlineError, { color: c.danger }]}>Could not load your allocation.</Text>
        ) : holdings.length === 0 ? (
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
        {/* Same rule as the Allocation card above. `history` falls back to [] on failure, so a
            failed /networth told someone with a year of saved snapshots that they had never saved
            one -- while the Net Worth card immediately above it, fed by that identical query,
            correctly showed '—'. Reliably reachable: 'networth' is deliberately excluded from the
            persistence allowlist, so there is never a cached value to fall back on. */}
        {netWorthUnknown ? (
          <Text style={[styles.inlineError, { color: c.danger }]}>
            Could not load your net worth history.
          </Text>
        ) : history.length < 2 ? (
          // Track C/C8: a snapshot is now taken automatically every day (NetWorthSnapshotSweepService),
          // so this is no longer "the first one you save" -- it just hasn't been a day or two yet.
          // "Save snapshot" above still exists as a same-day override, not the only way history starts.
          <EmptyState message="Building your net worth trend — check back in a day or two, or save today's snapshot now to add a point right away." />
        ) : (
          <TrendChart
            points={history.map((h) => ({ date: h.date, value: h.netWorth }))}
            width={chartWidth}
          />
        )}
      </Card>

      <Card style={styles.section}>
        <SectionHeading title="Holdings" />
        {accountsUnknown ? (
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
