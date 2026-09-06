import { useState } from 'react';
import {
  Pressable, RefreshControl, ScrollView, StyleSheet, Text, View,
} from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigation } from '@react-navigation/native';
import type { BottomTabNavigationProp } from '@react-navigation/bottom-tabs';
import { usePreventScreenCapture } from 'expo-screen-capture';
import { AnimatedNumber } from '../components/AnimatedNumber';
import { Button } from '../components/Button';
import { Card, EmptyState } from '../components/Card';
import { SkeletonBudgetCard } from '../components/skeletons/Skeletons';
import { OptionPickerModal } from '../components/OptionPickerModal';
import { ProgressBar } from '../components/ProgressBar';
import { TextField } from '../components/TextField';
import { budgetsApi, categoriesApi } from '../api/endpoints';
import { toUserMessage } from '../lib/apiError';
import { currentYearMonth, fmtCurrency, monthDateRange, monthLabel } from '../lib/format';
import { hapticError, hapticSuccess, hapticWarning } from '../lib/haptics';
import { useSingleFlight } from '../lib/useSingleFlight';
import { useTransientFlag } from '../lib/useTransientFlag';
import { parsePositiveAmount } from '../lib/validation';
import { radius, spacing, useTheme } from '../theme';
import type { AppTabParamList } from '../navigation/types';

/**
 * Port of frontend/src/pages/Budgets.tsx.
 *
 * One deliberate difference: the web page takes the category as free text, so a typo silently
 * creates a budget against a category no transaction will ever be filed under -- it reads as a
 * budget that never fills up. Here the category comes from the same picker the import review uses,
 * which is both the better touch affordance and the fix for that.
 */
export function BudgetsScreen() {
  // D3 (Track D security cleanup). Budget amounts and category spend are financial figures like
  // any other -- same screenshot/screen-recording exposure Dashboard/Accounts/Statement History
  // already guard against.
  usePreventScreenCapture();
  const c = useTheme();
  const queryClient = useQueryClient();
  // BudgetsScreen lives inside the More stack (see AppTabs.tsx), not on the tab bar itself --
  // getParent() reaches the tab navigator the same way StatementHistoryScreen's own re-import
  // link does, for the identical reason (Track C/C4).
  const navigation = useNavigation();
  const [category, setCategory] = useState<string | null>(null);
  const [limit, setLimit] = useState('');
  const [pickerOpen, setPickerOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [saved, confirmSaved] = useTransientFlag();
  const singleFlight = useSingleFlight();

  const { data: budgets = [], isLoading, isError, isFetching, refetch } = useQuery({
    queryKey: ['budgets'],
    queryFn: () => budgetsApi.list(),
  });

  const { data: categories = [] } = useQuery({
    queryKey: ['categories'],
    queryFn: () => categoriesApi.list(),
    staleTime: 5 * 60_000, // the category list barely changes within a session
  });

  async function save() {
    const amount = parsePositiveAmount(limit);
    if (!category) {
      setError('Pick a category first.');
      hapticWarning();
      return;
    }
    if (amount === null) {
      setError('Monthly limit must be a number greater than zero.');
      hapticWarning();
      return;
    }
    setError(null);
    await singleFlight(async () => {
      setSaving(true);
      try {
        await budgetsApi.upsert(category, amount);
        setCategory(null);
        setLimit('');
        confirmSaved();
        hapticSuccess();
        // Dashboard's budget widget and the health score/notifications both read this -- see the
        // web page's own comment. 'budgets' alone would leave the Dashboard stale until its cache
        // aged out.
        void queryClient.invalidateQueries({ queryKey: ['budgets'] });
        void queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
      } catch (e) {
        setError(toUserMessage(e, 'Could not save this budget. Try again.'));
        // hapticError, not hapticWarning -- this is the server rejecting a well-formed submit, not
        // the client-side "form isn't complete yet" case the two validation checks above cover.
        hapticError();
      } finally {
        setSaving(false);
      }
    });
  }

  return (
    <ScrollView
      style={{ backgroundColor: c.bg }}
      contentContainerStyle={styles.content}
      keyboardShouldPersistTaps="handled"
      refreshControl={
        <RefreshControl refreshing={isFetching && !isLoading} onRefresh={() => void refetch()} tintColor={c.primary} />
      }
    >
      <Card>
        <Text style={[styles.fieldLabel, { color: c.muted }]}>Category</Text>
        <Pressable
          onPress={() => setPickerOpen(true)}
          style={[styles.picker, { backgroundColor: c.inputBg, borderColor: c.border }]}
          accessibilityRole="button"
          accessibilityLabel={category ? `Category: ${category}. Change` : 'Choose a category'}
        >
          <Text style={[styles.pickerText, { color: category ? c.ink : c.muted }]}>
            {category ?? 'Choose a category'}
          </Text>
          <Text style={[styles.chevron, { color: c.muted }]} accessibilityElementsHidden importantForAccessibility="no">
            ›
          </Text>
        </Pressable>

        <View style={styles.limitField}>
          <TextField
            label="Monthly limit"
            value={limit}
            onChangeText={setLimit}
            keyboardType="decimal-pad"
            placeholder="0"
          />
        </View>

        <Button label={saving ? 'Saving…' : 'Set Budget'} onPress={() => void save()} loading={saving} />

        {error ? <Text style={[styles.error, { color: c.danger }]}>{error}</Text> : null}
        {saved ? <Text style={[styles.saved, { color: c.success }]}>Saved.</Text> : null}
      </Card>

      <View style={styles.list}>
        {isLoading ? (
          <>
            <SkeletonBudgetCard />
            <SkeletonBudgetCard />
            <SkeletonBudgetCard />
          </>
        ) : isError ? (
          <Card>
            <Text style={[styles.error, { color: c.danger }]}>Could not load budgets.</Text>
          </Card>
        ) : budgets.length === 0 ? (
          <Card>
            <EmptyState message="No budgets set yet. Set one above to start tracking a category." />
          </Card>
        ) : (
          budgets.map((b) => {
            const pct = b.monthlyLimit > 0 ? (b.spentThisMonth / b.monthlyLimit) * 100 : 0;
            // Same three thresholds as the web page: over budget, close to it, comfortable.
            const barColor = pct >= 100 ? c.danger : pct >= 90 ? c.warning : c.success;
            const remaining = b.monthlyLimit - b.spentThisMonth;
            return (
              <Card key={b.id} style={styles.budgetCard}>
                {/* One accessible node: the category, the amounts and the bar are a single fact,
                    and swiping through them separately loses which spend belongs to which limit.
                    A Pressable rather than the plain View this used to be (Track C/C4): this
                    month's spend against THIS category is exactly what a budget card is already
                    showing a total of, so it's the drill-through's most direct source. */}
                <Pressable
                  accessibilityRole="button"
                  accessibilityLabel={`${b.categoryName}: ${fmtCurrency(b.spentThisMonth)} spent of ${fmtCurrency(
                    b.monthlyLimit
                  )}. ${
                    remaining >= 0 ? `${fmtCurrency(remaining)} left` : `${fmtCurrency(-remaining)} over budget`
                  }`}
                  accessibilityHint="Opens these transactions"
                  android_ripple={{ color: c.border }}
                  onPress={() => {
                    const month = currentYearMonth();
                    const { dateFrom, dateTo } = monthDateRange(month);
                    navigation.getParent<BottomTabNavigationProp<AppTabParamList>>()?.navigate('Transactions', {
                      filters: {
                        categoryId: b.categoryId, dateFrom, dateTo,
                        label: `${b.categoryName} · ${monthLabel(month)}`,
                        nonce: Date.now(),
                      },
                    });
                  }}
                >
                  <View style={styles.budgetHeader}>
                    <Text style={[styles.budgetName, { color: c.ink }]} numberOfLines={1}>
                      {b.categoryName}
                    </Text>
                    <View style={styles.budgetAmountsRow}>
                      <AnimatedNumber value={b.spentThisMonth} style={[styles.budgetAmounts, { color: c.muted }]} />
                      <Text style={[styles.budgetAmounts, { color: c.muted }]}> / </Text>
                      <AnimatedNumber value={b.monthlyLimit} style={[styles.budgetAmounts, { color: c.muted }]} />
                    </View>
                  </View>
                  <ProgressBar pct={pct} color={barColor} />
                  <Text style={[styles.budgetFoot, { color: remaining >= 0 ? c.muted : c.danger }]}>
                    {remaining >= 0
                      ? `${fmtCurrency(remaining)} left this month`
                      : `${fmtCurrency(-remaining)} over budget`}
                  </Text>
                </Pressable>
              </Card>
            );
          })
        )}
      </View>

      <OptionPickerModal
        visible={pickerOpen}
        title="Category"
        options={categories.map((x) => x.name)}
        selected={category}
        onSelect={(name) => {
          setCategory(name);
          setPickerOpen(false);
        }}
        onClose={() => setPickerOpen(false)}
      />
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  centered: { flex: 1, alignItems: 'center', justifyContent: 'center' },
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
  limitField: { marginTop: spacing.sm },
  error: { fontSize: 13, marginTop: spacing.sm },
  saved: { fontSize: 13, marginTop: spacing.sm },
  list: { marginTop: spacing.md, gap: spacing.sm },
  budgetCard: {},
  budgetHeader: {
    flexDirection: 'row',
    alignItems: 'baseline',
    justifyContent: 'space-between',
    marginBottom: spacing.sm,
  },
  budgetName: { fontSize: 14, fontWeight: '600', flex: 1, marginRight: spacing.sm },
  budgetAmountsRow: { flexDirection: 'row', alignItems: 'baseline' },
  budgetAmounts: { fontSize: 12 },
  budgetFoot: { fontSize: 11, marginTop: 6 },
});
