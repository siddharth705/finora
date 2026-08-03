import { useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useQuery } from '@tanstack/react-query';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { accountsApi } from '../api/endpoints';
import { Card, EmptyState } from '../components/Card';
import { fmtCurrency, fmtDate } from '../lib/format';
import { radius, spacing, useTheme } from '../theme';

/**
 * How long a revealed account number stays visible before hiding again -- a common banking UX
 * pattern, ported from frontend/src/pages/Setup.tsx's AUTO_REMASK_MS. Also re-masks on unmount,
 * which falls out of `revealed` living in component state.
 *
 * Worth being precise about what this protects: Finora never stores a true, full account number.
 * What's revealed is the already-masked value the bank's own export or the import pipeline
 * produced (e.g. "••••4802"), so this is shoulder-surfing hygiene on a shared screen, not a
 * security boundary.
 */
const AUTO_REMASK_MS = 8000;

const ACCOUNT_TYPE_LABEL: Record<string, string> = {
  SAVINGS: 'Savings',
  CREDIT_CARD: 'Credit Card',
  WALLET: 'Wallet',
  INVESTMENT: 'Investment',
};

export function AccountsScreen() {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const [revealed, setRevealed] = useState<Set<string>>(new Set());
  const remaskTimers = useRef<Map<string, ReturnType<typeof setTimeout>>>(new Map());

  const { data: accounts = [], isLoading, isError } = useQuery({
    queryKey: ['accounts'],
    queryFn: () => accountsApi.list(),
  });

  useEffect(() => {
    // Every pending timer is dropped when this screen unmounts -- combined with `revealed` living
    // only in state, that satisfies "mask again when the user leaves" with no extra code.
    const timers = remaskTimers.current;
    return () => timers.forEach(clearTimeout);
  }, []);

  function toggleRevealed(id: string) {
    setRevealed((prev) => {
      const next = new Set(prev);
      const existing = remaskTimers.current.get(id);
      if (existing) {
        clearTimeout(existing);
        remaskTimers.current.delete(id);
      }

      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
        const timer = setTimeout(() => {
          setRevealed((cur) => {
            const copy = new Set(cur);
            copy.delete(id);
            return copy;
          });
          remaskTimers.current.delete(id);
        }, AUTO_REMASK_MS);
        remaskTimers.current.set(id, timer);
      }
      return next;
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
      contentContainerStyle={[styles.content, { paddingTop: insets.top + spacing.md }]}
    >
      <Text style={[styles.title, { color: c.ink }]}>Accounts</Text>

      {isError ? (
        <Text style={[styles.error, { color: c.danger }]}>Could not load accounts.</Text>
      ) : accounts.length === 0 ? (
        <Card>
          <EmptyState message="No accounts yet. Importing a statement creates one automatically." />
        </Card>
      ) : (
        accounts.map((a) => {
          const isRevealed = revealed.has(a.id);
          const lastImported = fmtDate(a.lastImportedAt);
          return (
            <Card key={a.id} style={styles.accountCard}>
              <View style={styles.accountHeader}>
                <View style={[styles.bankBadge, { backgroundColor: a.bank?.colorHex || c.primary }]}>
                  <Text style={styles.bankInitials}>{a.bank?.initials || '?'}</Text>
                </View>
                <View style={styles.accountTitleBlock}>
                  <Text style={[styles.accountName, { color: c.ink }]} numberOfLines={1}>
                    {a.name}
                  </Text>
                  <Text style={[styles.accountType, { color: c.muted }]}>
                    {ACCOUNT_TYPE_LABEL[a.accountType] ?? a.accountType}
                    {a.bank?.shortName ? ` · ${a.bank.shortName}` : ''}
                  </Text>
                </View>
              </View>

              <Text style={[styles.balance, { color: c.ink }]}>{fmtCurrency(a.balance)}</Text>
              {a.accountType === 'CREDIT_CARD' && a.creditLimit ? (
                <Text style={[styles.detail, { color: c.muted }]}>
                  Limit {fmtCurrency(a.creditLimit)}
                </Text>
              ) : null}

              {a.accountNumberMasked ? (
                <Pressable onPress={() => toggleRevealed(a.id)} style={styles.numberRow} hitSlop={6}>
                  <Text style={[styles.detail, { color: c.muted }]}>
                    {isRevealed ? a.accountNumberMasked : '•••• ••••'}
                  </Text>
                  <Text style={[styles.reveal, { color: c.primary }]}>
                    {isRevealed ? 'Hide' : 'Show'}
                  </Text>
                </Pressable>
              ) : null}

              {a.accountHolderName ? (
                <Text style={[styles.detail, { color: c.muted }]}>{a.accountHolderName}</Text>
              ) : null}

              <View style={[styles.statsRow, { borderTopColor: c.border }]}>
                <Text style={[styles.stat, { color: c.muted }]}>
                  {a.transactionsCount.toLocaleString('en-IN')} transactions
                </Text>
                <Text style={[styles.stat, { color: c.muted }]}>
                  {lastImported ? `Last import ${lastImported}` : 'Never imported'}
                </Text>
              </View>
            </Card>
          );
        })
      )}

      {/* Add/edit/delete are deliberately absent here. The web Setup page carries a full
          create form (bank search, account type, opening balance, credit limit, due date,
          holder name, branch, IFSC) plus inline rename and delete -- that's a screen's worth
          of forms on its own, and the roadmap puts account management in the same phase as
          the rest of the CRUD surfaces. This is the read view Phase 2 calls for. */}
      <Text style={[styles.note, { color: c.muted }]}>
        Accounts are created automatically when you import a statement.
      </Text>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  centered: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  content: { padding: spacing.md, paddingBottom: spacing.xl },
  title: { fontSize: 22, fontWeight: '700', marginBottom: spacing.md },
  error: { fontSize: 13 },
  accountCard: { marginBottom: spacing.sm },
  accountHeader: { flexDirection: 'row', alignItems: 'center', marginBottom: spacing.sm },
  bankBadge: {
    width: 36,
    height: 36,
    borderRadius: radius.md,
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: spacing.sm,
  },
  bankInitials: { color: '#fff', fontWeight: '700', fontSize: 13 },
  accountTitleBlock: { flex: 1 },
  accountName: { fontSize: 15, fontWeight: '600' },
  accountType: { fontSize: 11, marginTop: 1 },
  balance: { fontSize: 22, fontWeight: '700' },
  detail: { fontSize: 12, marginTop: 4 },
  numberRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, marginTop: 4 },
  reveal: { fontSize: 12, fontWeight: '600' },
  statsRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    borderTopWidth: StyleSheet.hairlineWidth,
    marginTop: spacing.sm,
    paddingTop: spacing.sm,
  },
  stat: { fontSize: 11 },
  note: { fontSize: 11, textAlign: 'center', marginTop: spacing.sm },
});
