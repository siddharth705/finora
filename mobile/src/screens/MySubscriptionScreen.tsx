import { View, Text, StyleSheet, Pressable, Platform, Linking } from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { billingApi } from '../api/endpoints';
import { restorePurchases } from '../lib/revenueCat';
import { fmtDate } from '../lib/format';
import { useTheme } from '../theme';

const IOS_MANAGE_SUBSCRIPTIONS_URL = 'itms-apps://apps.apple.com/account/subscriptions';
const ANDROID_MANAGE_SUBSCRIPTIONS_URL = 'https://play.google.com/store/account/subscriptions';

/** Mobile equivalent of frontend/src/pages/Billing.tsx, structurally different by design (spec
 *  §2/§8): neither App Store nor Play Store policy allows an in-app cancel button for an IAP
 *  subscription, so this only ever deep-links out to the OS's own subscription management. A
 *  Razorpay-owned subscription is read-only here for the same reason mobile never offers the
 *  Paywall to one -- design spec §6.3/§6.4's ownership-source rule (§2.1, invariant 2). */
export function MySubscriptionScreen() {
  const c = useTheme();
  const queryClient = useQueryClient();
  const { data: subscription, isLoading } = useQuery({
    queryKey: ['my-subscription'],
    queryFn: () => billingApi.mySubscription(),
  });

  async function handleManageSubscription() {
    const url = Platform.OS === 'ios' ? IOS_MANAGE_SUBSCRIPTIONS_URL : ANDROID_MANAGE_SUBSCRIPTIONS_URL;
    await Linking.openURL(url);
  }

  async function handleRestore() {
    await restorePurchases();
    await queryClient.invalidateQueries({ queryKey: ['my-subscription'] });
    await queryClient.invalidateQueries({ queryKey: ['entitlements'] });
  }

  if (isLoading || !subscription) return null;

  return (
    <View style={[styles.container, { backgroundColor: c.bg }]}>
      <Text style={[styles.planName, { color: c.ink }]}>{subscription.planName ?? subscription.planCode}</Text>

      {subscription.renewalDate && (
        <Text style={[styles.note, { color: c.muted }]}>
          {subscription.hasBillingSubscription && !subscription.autoRenew
            ? `Ends ${fmtDate(subscription.renewalDate)} — won't renew`
            : `Renews ${fmtDate(subscription.renewalDate)}`}
        </Text>
      )}

      {subscription.hasBillingSubscription && subscription.paymentProvider === 'RAZORPAY' && (
        <Text style={[styles.note, { color: c.muted }]}>
          This subscription is managed on web. Open the Billing page in a browser to make changes.
        </Text>
      )}

      {subscription.hasBillingSubscription && subscription.paymentProvider === 'REVENUECAT' && (
        <Pressable accessibilityRole="button" onPress={handleManageSubscription} style={[styles.button, { borderColor: c.border }]}>
          <Text style={{ color: c.ink }}>Manage subscription</Text>
        </Pressable>
      )}

      {/* Not shown for a Razorpay-owned subscription -- there is nothing an App Store/Play Store
          restore could do for a web-purchased plan, and showing it would just invite a confusing
          no-op tap. Shown both for FREE (no billing subscription -- a lapsed or not-yet-synced IAP
          purchase) and REVENUECAT-owned. */}
      {subscription.paymentProvider !== 'RAZORPAY' && (
        <Pressable accessibilityRole="button" onPress={handleRestore} style={[styles.button, { borderColor: c.border }]}>
          <Text style={{ color: c.ink }}>Restore Purchases</Text>
        </Pressable>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, padding: 16, gap: 12 },
  planName: { fontSize: 20, fontWeight: '700' },
  note: { fontSize: 13 },
  button: { borderWidth: 1, borderRadius: 12, paddingVertical: 12, alignItems: 'center', marginTop: 8 },
});
