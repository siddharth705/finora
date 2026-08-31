import { useState } from 'react';
import {
  ActivityIndicator, Alert, Pressable, RefreshControl, ScrollView, StyleSheet, Text, View,
} from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { AmountPromptModal } from '../components/AmountPromptModal';
import { Button } from '../components/Button';
import { Card, EmptyState } from '../components/Card';
import { DateField } from '../components/DateField';
import { ProgressBar } from '../components/ProgressBar';
import { TextField } from '../components/TextField';
import { goalsApi } from '../api/endpoints';
import { toUserMessage } from '../lib/apiError';
import { fmtCurrency, fmtDate } from '../lib/format';
import { useLargeFontScale } from '../lib/useLargeFontScale';
import { useSingleFlight } from '../lib/useSingleFlight';
import { parsePositiveAmount } from '../lib/validation';
import { spacing, useTheme } from '../theme';
import type { Goal } from '../types';

/** Port of frontend/src/pages/Goals.tsx. */
export function GoalsScreen() {
  const c = useTheme();
  const largeText = useLargeFontScale();
  const queryClient = useQueryClient();
  // Guards every write below against a same-frame double tap -- see useSingleFlight. The `saving`
  // and `contributing` flags drive what the user sees; this is what stops the second request.
  const singleFlight = useSingleFlight();

  const [name, setName] = useState('');
  const [target, setTarget] = useState('');
  const [starting, setStarting] = useState('');
  const [targetDate, setTargetDate] = useState<string | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [contributingTo, setContributingTo] = useState<Goal | null>(null);
  const [contributionError, setContributionError] = useState<string | null>(null);
  const [contributing, setContributing] = useState(false);

  const { data: goals = [], isLoading, isError, isFetching, refetch } = useQuery({
    queryKey: ['goals'],
    queryFn: () => goalsApi.list(),
  });

  // The Dashboard reads goals from this same cache and folds goal progress into the summary --
  // see the web page's own comment. Both keys, or funding a goal here leaves the Dashboard stale.
  function invalidateSharedCaches() {
    void queryClient.invalidateQueries({ queryKey: ['goals'] });
    void queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
  }

  async function addGoal() {
    const targetAmount = parsePositiveAmount(target);
    if (!name.trim()) {
      setError('Give this goal a name.');
      return;
    }
    if (targetAmount === null) {
      setError('Target amount must be a number greater than zero.');
      return;
    }
    // Starting amount is optional, so blank means zero -- but a typed value that isn't a number
    // must not silently become zero either.
    const startingAmount = starting.trim() ? parsePositiveAmount(starting) : 0;
    if (startingAmount === null) {
      setError('Starting amount must be a number greater than zero, or left blank.');
      return;
    }

    setError(null);
    await singleFlight(async () => {
      setSaving(true);
      try {
        await goalsApi.create({
          name: name.trim(),
          targetAmount,
          currentAmount: startingAmount,
          targetDate: targetDate ?? undefined,
        });
        setName(''); setTarget(''); setStarting(''); setTargetDate(null);
        setFormOpen(false);
        invalidateSharedCaches();
      } catch (e) {
        setError(toUserMessage(e, 'Could not create this goal. Try again.'));
      } finally {
        setSaving(false);
      }
    });
  }

  /**
   * The web version calls `window.prompt('Contribution amount:')`, which does not exist in React
   * Native -- the plan calls out replacing it with a real native input as required, not optional.
   */
  async function submitContribution(amount: number) {
    if (!contributingTo) return;
    setContributionError(null);
    await singleFlight(async () => {
      setContributing(true);
      try {
        await goalsApi.addContribution(contributingTo.id, amount);
        setContributingTo(null);
        invalidateSharedCaches();
      } catch (e) {
        setContributionError(toUserMessage(e, 'Could not record this contribution. Try again.'));
      } finally {
        setContributing(false);
      }
    });
  }

  function confirmDelete(g: Goal) {
    // Alert.alert replaces the web's window.confirm(), same substitution as LedgerScreen's delete.
    Alert.alert('Delete this goal?', `"${g.name}" and its contribution history can't be recovered.`, [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Delete', style: 'destructive', onPress: () => void remove(g) },
    ]);
  }

  async function remove(g: Goal) {
    await singleFlight(async () => {
      setError(null);
      try {
        await goalsApi.remove(g.id);
        invalidateSharedCaches();
      } catch (e) {
        setError(toUserMessage(e, 'Could not delete this goal. Try again.'));
      }
    });
  }

  if (isLoading) {
    return (
      <View style={[styles.centered, { backgroundColor: c.bg }]}>
        <ActivityIndicator size="large" color={c.primary} />
      </View>
    );
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
      <View style={styles.header}>
        <Pressable
          onPress={() => setFormOpen((o) => !o)}
          hitSlop={12}
          accessibilityRole="button"
          accessibilityState={{ expanded: formOpen }}
        >
          <Text style={[styles.headerAction, { color: c.primary }]}>{formOpen ? 'Cancel' : '+ New goal'}</Text>
        </Pressable>
      </View>

      {/* Collapsed by default, unlike the web page's always-visible four-column form: at phone
          width that form is most of the first screen, pushing the goals themselves below the fold
          on a page whose point is looking at them. */}
      {formOpen ? (
        <Card style={styles.formCard}>
          <TextField label="Name" value={name} onChangeText={setName} placeholder="Emergency fund" />
          <TextField
            label="Target amount"
            value={target}
            onChangeText={setTarget}
            keyboardType="decimal-pad"
            placeholder="0"
          />
          <TextField
            label="Starting amount (optional)"
            value={starting}
            onChangeText={setStarting}
            keyboardType="decimal-pad"
            placeholder="0"
          />
          <DateField
            label="Target date (optional)"
            value={targetDate}
            onChange={setTargetDate}
            minimumDate={new Date()}
          />
          <View style={styles.formButton}>
            <Button label={saving ? 'Adding…' : 'Add Goal'} onPress={() => void addGoal()} loading={saving} />
          </View>
        </Card>
      ) : null}

      {error ? <Text style={[styles.error, { color: c.danger }]}>{error}</Text> : null}

      <View style={styles.list}>
        {isError ? (
          <Card>
            <Text style={[styles.error, { color: c.danger }]}>Could not load goals.</Text>
          </Card>
        ) : goals.length === 0 ? (
          <Card>
            <EmptyState message="No goals yet. Create one to start tracking progress toward it." />
          </Card>
        ) : (
          goals.map((g) => {
            const pct = g.targetAmount > 0 ? Math.min(100, (g.currentAmount / g.targetAmount) * 100) : 0;
            const due = fmtDate(g.targetDate);
            return (
              <Card key={g.id}>
                <View
                  accessible
                  accessibilityLabel={`${g.name}: ${fmtCurrency(g.currentAmount)} of ${fmtCurrency(
                    g.targetAmount
                  )}, ${pct.toFixed(0)} percent complete${due ? `, target ${due}` : ''}`}
                >
                  <View style={styles.goalHeader}>
                    <Text style={[styles.goalName, { color: c.ink }]} numberOfLines={largeText ? 2 : 1}>
                      {g.name}
                    </Text>
                    <Text style={[styles.goalAmounts, { color: c.mutedInk }]}>
                      {fmtCurrency(g.currentAmount)} / {fmtCurrency(g.targetAmount)}
                    </Text>
                  </View>
                  <ProgressBar pct={pct} color={c.success} />
                  <Text style={[styles.goalMeta, { color: c.mutedInk }]}>
                    {pct.toFixed(0)}% complete{due ? ` · target ${due}` : ''}
                  </Text>
                </View>

                <View style={[styles.goalActions, { borderTopColor: c.border }]}>
                  <Pressable
                    onPress={() => {
                      setContributionError(null);
                      setContributingTo(g);
                    }}
                    style={[styles.action, { borderColor: c.border }]}
                    accessibilityRole="button"
                    accessibilityLabel={`Add contribution to ${g.name}`}
                  >
                    <Text style={[styles.actionText, { color: c.primary }]}>Add Contribution</Text>
                  </Pressable>
                  <Pressable
                    onPress={() => confirmDelete(g)}
                    style={[styles.action, { borderColor: c.danger }]}
                    accessibilityRole="button"
                    accessibilityLabel={`Delete ${g.name}`}
                  >
                    <Text style={[styles.actionText, { color: c.danger }]}>Delete</Text>
                  </Pressable>
                </View>
              </Card>
            );
          })
        )}
      </View>

      {/* Mounted only while open, so each contribution starts from an empty field -- see the
          component's own note. */}
      {contributingTo ? (
        <AmountPromptModal
          title="Add contribution"
          subtitle={contributingTo.name}
          confirmLabel="Add"
          error={contributionError}
          submitting={contributing}
          onSubmit={(amount) => void submitContribution(amount)}
          onClose={() => setContributingTo(null)}
        />
      ) : null}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  centered: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  content: { padding: spacing.md, paddingBottom: spacing.xl },
  header: { alignItems: 'flex-end', marginBottom: spacing.sm },
  headerAction: { fontSize: 14, fontWeight: '600' },
  formCard: { marginBottom: spacing.md },
  formButton: { marginTop: spacing.sm },
  error: { fontSize: 13, marginBottom: spacing.sm },
  list: { gap: spacing.sm },
  goalHeader: {
    flexDirection: 'row',
    alignItems: 'baseline',
    justifyContent: 'space-between',
    marginBottom: spacing.sm,
  },
  goalName: { fontSize: 15, fontWeight: '600', flex: 1, marginRight: spacing.sm },
  goalAmounts: { fontSize: 12 },
  goalMeta: { fontSize: 11, marginTop: 6 },
  goalActions: {
    flexDirection: 'row',
    gap: spacing.sm,
    borderTopWidth: StyleSheet.hairlineWidth,
    marginTop: spacing.sm,
    paddingTop: spacing.sm,
  },
  action: {
    flex: 1,
    borderWidth: 1,
    borderRadius: 8,
    minHeight: 44,
    alignItems: 'center',
    justifyContent: 'center',
  },
  actionText: { fontSize: 13, fontWeight: '600' },
});
