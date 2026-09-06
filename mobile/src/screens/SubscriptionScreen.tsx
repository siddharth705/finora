import { useQuery } from '@tanstack/react-query';
import { billingApi } from '../api/endpoints';
import { PaywallScreen } from './PaywallScreen';
import { MySubscriptionScreen } from './MySubscriptionScreen';

/** Subscription billing V4. The one thing actually registered in MoreScreen's MENU_ITEMS --
 *  that array is a static {label, route} list with no conditional-destination support (checked
 *  against the real file), so it can't route to PaywallScreen or MySubscriptionScreen depending on
 *  subscription state by itself. This picks between the two internally instead. */
export function SubscriptionScreen() {
  const { data: subscription, isLoading } = useQuery({
    queryKey: ['my-subscription'],
    queryFn: () => billingApi.mySubscription(),
  });

  if (isLoading) return null;
  return subscription?.hasBillingSubscription ? <MySubscriptionScreen /> : <PaywallScreen />;
}
