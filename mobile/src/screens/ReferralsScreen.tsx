import { ActivityIndicator, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useQuery } from '@tanstack/react-query';
import * as Clipboard from 'expo-clipboard';
import Ionicons from '@expo/vector-icons/Ionicons';
import { Card } from '../components/Card';
import { MetricTile } from '../components/AccountUI';
import { referralsApi } from '../api/endpoints';
import { useTransientFlag } from '../lib/useTransientFlag';
import { radius, spacing, useTheme } from '../theme';

/**
 * Refer & Earn MVP (mobile) -- ported from frontend/src/pages/Referrals.tsx, cut down to the same
 * scope: a shareable code, a copy action, and how many people have joined through it. No wallet
 * balance, no per-referral list, no status badges (see ReferralService's own doc comment on the
 * backend for the scope this replaced).
 *
 * Mobile has no equivalent of the web's `?ref=` URL param, so there's nothing to build a share
 * LINK out of -- the code itself is the thing to copy and hand to a friend, who types it into
 * their own Register screen's "Referral code (optional)" field.
 */
export function ReferralsScreen() {
  const c = useTheme();
  const [copied, triggerCopied] = useTransientFlag();

  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ['referrals-mine'],
    queryFn: () => referralsApi.mine(),
  });

  async function handleCopy() {
    if (!data?.code) return;
    await Clipboard.setStringAsync(data.code);
    triggerCopied();
  }

  if (isLoading) {
    return (
      <View style={[styles.centered, { backgroundColor: c.bg }]}>
        <ActivityIndicator size="large" color={c.primary} />
      </View>
    );
  }

  if (isError || !data) {
    return (
      <View style={[styles.centered, { backgroundColor: c.bg }]}>
        <Text style={[styles.message, { color: c.muted }]}>Couldn&apos;t load your referral code.</Text>
        <Pressable onPress={() => void refetch()} accessibilityRole="button">
          <Text style={[styles.retry, { color: c.primary }]}>Try again</Text>
        </Pressable>
      </View>
    );
  }

  return (
    <ScrollView style={{ backgroundColor: c.bg }} contentContainerStyle={styles.content}>
      {/* No in-screen title -- the native header (this route's own options={{ title: 'Refer &
          Earn' }} in AppTabs.tsx) already provides it, same as Profile/Settings. */}
      <Text style={[styles.subtitle, { color: c.muted }]}>
        Share Fynora with friends and see who joins.
      </Text>

      <Card style={styles.codeCard}>
        <View style={styles.codeHeader}>
          <View style={[styles.iconCircle, { backgroundColor: c.primaryLight }]}>
            <Ionicons name="gift-outline" size={18} color={c.primary} />
          </View>
          <View style={styles.codeHeaderText}>
            <Text style={[styles.cardLabel, { color: c.ink }]}>Your referral code</Text>
            <Text style={[styles.cardHint, { color: c.muted }]}>
              Anyone who signs up with this code is credited to you.
            </Text>
          </View>
        </View>

        <View style={[styles.codeRow, { backgroundColor: c.bg, borderColor: c.border }]}>
          <Text style={[styles.code, { color: c.ink }]} selectable accessibilityLabel={`Referral code ${data.code}`}>
            {data.code}
          </Text>
          <Pressable
            onPress={() => void handleCopy()}
            style={[styles.copyButton, { backgroundColor: c.primary }]}
            accessibilityRole="button"
            accessibilityLabel={copied ? 'Copied' : 'Copy referral code'}
          >
            <Ionicons name={copied ? 'checkmark' : 'copy-outline'} size={14} color={c.onPrimary} />
            <Text style={[styles.copyButtonText, { color: c.onPrimary }]}>{copied ? 'Copied' : 'Copy'}</Text>
          </Pressable>
        </View>
      </Card>

      <MetricTile label="Referrals" value={String(data.referralCount)} />
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  centered: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: spacing.lg, gap: spacing.sm },
  message: { fontSize: 14, textAlign: 'center' },
  retry: { fontSize: 13, fontWeight: '600' },
  content: { padding: spacing.md, paddingBottom: spacing.xl, gap: spacing.md },
  subtitle: { fontSize: 13 },
  codeCard: { gap: spacing.md },
  codeHeader: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  iconCircle: { width: 36, height: 36, borderRadius: 18, alignItems: 'center', justifyContent: 'center' },
  codeHeaderText: { flex: 1 },
  cardLabel: { fontSize: 14, fontWeight: '600' },
  cardHint: { fontSize: 11, marginTop: 2 },
  codeRow: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    borderWidth: 1, borderRadius: radius.md, paddingLeft: spacing.md, paddingRight: spacing.xs, minHeight: 52,
  },
  code: { fontSize: 18, fontWeight: '700', fontFamily: 'monospace', letterSpacing: 1 },
  copyButton: {
    flexDirection: 'row', alignItems: 'center', gap: 6,
    borderRadius: radius.md, paddingHorizontal: 12, paddingVertical: 10,
  },
  copyButtonText: { fontSize: 12, fontWeight: '600' },
});
