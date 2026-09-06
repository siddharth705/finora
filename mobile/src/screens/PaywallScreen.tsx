import { useState } from 'react';
import { View, Text, StyleSheet, Pressable, ActivityIndicator } from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { billingApi } from '../api/endpoints';
import { purchasePlan } from '../lib/revenueCat';
import { useTheme } from '../theme';

const PLANS = [
  { code: 'PLUS' as const, name: 'Plus', price: '₹399/mo' },
  { code: 'PREMIUM' as const, name: 'Premium', price: '₹799/mo' },
];

/** Mobile equivalent of frontend/src/pages/landing/Pricing.tsx, but purchase happens right here
 *  (design spec §8) -- unlike web, there's no separate marketing-site/app split on mobile. Only
 *  reachable post-auth (design spec §2's "purchase requires authentication" decision) and only
 *  shown when mySubscription() has no active paid payment_provider (design spec §6.3). */
export function PaywallScreen() {
  const c = useTheme();
  const queryClient = useQueryClient();
  const [purchasing, setPurchasing] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const { data: subscription } = useQuery({
    queryKey: ['my-subscription'],
    queryFn: () => billingApi.mySubscription(),
  });

  if (subscription?.hasBillingSubscription) {
    return null; // design spec §6.3/§6.4 -- caller (SubscriptionScreen) routes to MySubscriptionScreen instead
  }

  async function handlePurchase(planCode: 'PLUS' | 'PREMIUM') {
    setError(null);
    setPurchasing(planCode);
    try {
      await purchasePlan(planCode, 'MONTHLY');
      await queryClient.invalidateQueries({ queryKey: ['my-subscription'] });
      await queryClient.invalidateQueries({ queryKey: ['entitlements'] });
    } catch (e: any) {
      setError(e.message ?? 'Could not complete the purchase. Try again.');
    } finally {
      setPurchasing(null);
    }
  }

  return (
    <View style={[styles.container, { backgroundColor: c.bg }]}>
      {error && <Text style={[styles.error, { color: c.danger }]}>{error}</Text>}
      {PLANS.map((plan) => (
        <View key={plan.code} style={[styles.card, { borderColor: c.border, backgroundColor: c.card }]}>
          <Text style={[styles.planName, { color: c.ink }]}>{plan.name}</Text>
          <Text style={[styles.planPrice, { color: c.muted }]}>{plan.price}</Text>
          <Pressable accessibilityRole="button"
            disabled={purchasing !== null}
            onPress={() => handlePurchase(plan.code)}
            style={[styles.button, { backgroundColor: c.primary, opacity: purchasing ? 0.6 : 1 }]}
          >
            {purchasing === plan.code ? <ActivityIndicator color="#fff" /> : <Text style={styles.buttonText}>Subscribe</Text>}
          </Pressable>
        </View>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, padding: 16, gap: 16 },
  error: { fontSize: 13, marginBottom: 8 },
  card: { borderWidth: 1, borderRadius: 16, padding: 20, gap: 8 },
  planName: { fontSize: 18, fontWeight: '700' },
  planPrice: { fontSize: 14 },
  button: { marginTop: 12, borderRadius: 12, paddingVertical: 12, alignItems: 'center' },
  buttonText: { color: '#fff', fontWeight: '600' },
});
