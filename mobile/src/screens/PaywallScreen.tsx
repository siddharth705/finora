import { useEffect, useRef, useState } from 'react';
import { View, Text, StyleSheet, Pressable, ActivityIndicator } from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { billingApi } from '../api/endpoints';
import { purchasePlan } from '../lib/revenueCat';
import { useTheme } from '../theme';

const PLANS = [
  { code: 'PLUS' as const, name: 'Plus', price: '₹399/mo' },
  { code: 'PREMIUM' as const, name: 'Premium', price: '₹799/mo' },
];

// Design spec §6.1 step 5: activation is only ever trusted from the backend's verified RevenueCat
// webhook, never purchasePlan()'s own promise resolving -- there is a real race between the
// store's purchase sheet closing and the webhook actually landing. Mirrors web's own
// useActivationPoll (Billing.tsx): same 2s/30s cadence, same "check until the plan matches"
// criterion. `isCancelled` lets the caller stop early if the screen unmounts mid-poll --
// otherwise a user who navigates away right after tapping Subscribe would leave this looping
// in the background for up to 30s, still hitting the network and (without a guard at the call
// site too) trying to setState on an unmounted component. intervalMs/timeoutMs are parameters
// only so tests can override them; production always calls this with the defaults.
async function pollForActivation(
  expectedPlanCode: string,
  isCancelled: () => boolean,
  intervalMs = 2000,
  timeoutMs = 30000
): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline && !isCancelled()) {
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
    if (isCancelled()) return;
    const current = await billingApi.mySubscription();
    if (current.planCode === expectedPlanCode) return;
  }
}

/** Mobile equivalent of frontend/src/pages/landing/Pricing.tsx, but purchase happens right here
 *  (design spec §8) -- unlike web, there's no separate marketing-site/app split on mobile. Only
 *  reachable post-auth (design spec §2's "purchase requires authentication" decision) and only
 *  shown when mySubscription() has no active paid payment_provider (design spec §6.3). */
export function PaywallScreen() {
  const c = useTheme();
  const queryClient = useQueryClient();
  const [purchasing, setPurchasing] = useState<string | null>(null);
  const [activatingPlanCode, setActivatingPlanCode] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const mountedRef = useRef(true);
  useEffect(() => () => {
    mountedRef.current = false;
  }, []);

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
      if (!mountedRef.current) return;
      setActivatingPlanCode(planCode);
      await pollForActivation(planCode, () => !mountedRef.current);
      if (!mountedRef.current) return;
      await queryClient.invalidateQueries({ queryKey: ['my-subscription'] });
      await queryClient.invalidateQueries({ queryKey: ['entitlements'] });
    } catch (e: any) {
      if (mountedRef.current) setError(e.message ?? 'Could not complete the purchase. Try again.');
    } finally {
      if (mountedRef.current) {
        setPurchasing(null);
        setActivatingPlanCode(null);
      }
    }
  }

  const activatingPlanName = PLANS.find((p) => p.code === activatingPlanCode)?.name;

  return (
    <View style={[styles.container, { backgroundColor: c.bg }]}>
      {error && <Text style={[styles.error, { color: c.danger }]}>{error}</Text>}
      {activatingPlanName && (
        <Text style={[styles.note, { color: c.muted }]}>
          Activating your {activatingPlanName} plan… this can take a few seconds.
        </Text>
      )}
      {PLANS.map((plan) => (
        <View key={plan.code} style={[styles.card, { borderColor: c.border, backgroundColor: c.card }]}>
          <Text style={[styles.planName, { color: c.ink }]}>{plan.name}</Text>
          <Text style={[styles.planPrice, { color: c.muted }]}>{plan.price}</Text>
          <Pressable accessibilityRole="button"
            disabled={purchasing !== null || activatingPlanCode !== null}
            onPress={() => handlePurchase(plan.code)}
            style={[styles.button, { backgroundColor: c.primary, opacity: purchasing || activatingPlanCode ? 0.6 : 1 }]}
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
  note: { fontSize: 13, marginBottom: 8 },
  card: { borderWidth: 1, borderRadius: 16, padding: 20, gap: 8 },
  planName: { fontSize: 18, fontWeight: '700' },
  planPrice: { fontSize: 14 },
  button: { marginTop: 12, borderRadius: 12, paddingVertical: 12, alignItems: 'center' },
  buttonText: { color: '#fff', fontWeight: '600' },
});
