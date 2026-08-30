import { useState } from 'react';
import {
  Pressable, RefreshControl, ScrollView, StyleSheet, Text, View,
} from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Button } from '../components/Button';
import { Card, EmptyState } from '../components/Card';
import { SkeletonBudgetCard } from '../components/skeletons/Skeletons';
import { OptionPickerModal } from '../components/OptionPickerModal';
import { ProgressBar } from '../components/ProgressBar';
import { TextField } from '../components/TextField';
import { budgetsApi, categoriesApi } from '../api/endpoints';
import { toUserMessage } from '../lib/apiError';
import { fmtCurrency } from '../lib/format';
import { hapticSuccess, hapticWarning } from '../lib/haptics';
import { useSingleFlight } from '../lib/useSingleFlight';
import { useTransientFlag } from '../lib/useTransientFlag';
import { parsePositiveAmount } from '../lib/validation';
import { radius, spacing, useTheme } from '../theme';

/**
 * Port of frontend/src/pages/Budgets.tsx.
 *
 * One deliberate difference: the web page takes the category as free text, so a typo silently
 * creates a budget against a category no transaction will ever be filed under -- it reads as a
 * budget that never fills up. Here the category comes from the same picker the import review uses,
 * which is both the better touch affordance and the fix for that.
 */
export function BudgetsScreen() {
  const c = useTheme();
  const queryClient = useQueryClient();
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
                    and swiping through them separately loses which spend belongs to which limit. */}
                <View
                  accessible
                  accessibilityLabel={`${b.categoryName}: ${fmtCurrency(b.spentThisMonth)} spent of ${fmtCurrency(
                    b.monthlyLimit
                  )}. ${
                    remaining >= 0 ? `${fmtCurrency(remaining)} left` : `${fmtCurrency(-remaining)} over budget`
                  }`}
                >
                  <View style={styles.budgetHeader}>
                    <Text style={[styles.budgetName, { color: c.ink }]} numberOfLines={1}>
                      {b.categoryName}
                    </Text>
                    <Text style={[styles.budgetAmounts, { color: c.muted }]}>
                      {fmtCurrency(b.spentThisMonth)} / {fmtCurrency(b.monthlyLimit)}
                    </Text>
                  </View>
                  <ProgressBar pct={pct} color={barColor} />
                  <Text style={[styles.budgetFoot, { color: remaining >= 0 ? c.muted : c.danger }]}>
                    {remaining >= 0
                      ? `${fmtCurrency(remaining)} left this month`
                      : `${fmtCurrency(-remaining)} over budget`}
                  </Text>
                </View>
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
  budgetAmounts: { fontSize: 12 },
  budgetFoot: { fontSize: 11, marginTop: 6 },
});
