import Purchases, { type PurchasesPackage } from 'react-native-purchases';

const REVENUECAT_API_KEY = process.env.EXPO_PUBLIC_REVENUECAT_API_KEY;

/** Subscription billing V4 (design spec §2/§6.1). appUserID is ALWAYS the real, authenticated
 *  Fynora user id -- never RevenueCat's own anonymous $RCAnonymousID. Called once at sign-in,
 *  mirroring how the backend's Razorpay integration embeds the raw user id (notes.fynoraUserId)
 *  rather than a separate mapping id. */
export function configureRevenueCat(fynoraUserId: string): void {
  if (!REVENUECAT_API_KEY) {
    throw new Error('EXPO_PUBLIC_REVENUECAT_API_KEY is not set.');
  }
  Purchases.configure({ apiKey: REVENUECAT_API_KEY, appUserID: fynoraUserId });
}

function packageIdentifierFor(planCode: string, billingCycle: string): string {
  return `${planCode.toLowerCase()}_${billingCycle.toLowerCase()}`;
}

/** Opens the OS's native purchase sheet for the given plan/cycle. Resolving does NOT mean the
 *  plan is active -- activation only ever comes from the backend's verified RevenueCat webhook
 *  (design spec §6.1 step 5), same rule as web's openRazorpayCheckout(). */
export async function purchasePlan(planCode: 'PLUS' | 'PREMIUM', billingCycle: 'MONTHLY' | 'YEARLY'): Promise<void> {
  const offerings = await Purchases.getOfferings();
  const target = packageIdentifierFor(planCode, billingCycle);
  const pkg = offerings.current?.availablePackages.find(
    (p: PurchasesPackage) => p.identifier === target || p.product.identifier === target
  );
  if (!pkg) {
    throw new Error(`No RevenueCat offering package found for ${target}.`);
  }
  await Purchases.purchasePackage(pkg);
}

export async function restorePurchases(): Promise<void> {
  await Purchases.restorePurchases();
}
