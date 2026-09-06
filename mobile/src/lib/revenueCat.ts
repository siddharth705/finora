import Purchases, { type PurchasesPackage } from 'react-native-purchases';

const REVENUECAT_API_KEY = process.env.EXPO_PUBLIC_REVENUECAT_API_KEY;

// RevenueCat's own docs (identifying-customers.md): "You should configure the SDK only once in
// your code." AuthContext calls configureRevenueCat() from two convergence points (a cold-start
// restore of an already-signed-in session, and every fresh login/register/reactivate/Google/
// Apple) precisely because either one might be the first authenticated moment in this process --
// this guard is what makes calling it from both safe. Module-level, same pattern already
// established by GoogleSignInButton.tsx's own ensureConfigured().
let configured = false;

/** Subscription billing V4 (design spec §2/§6.1). appUserID is ALWAYS the real, authenticated
 *  Fynora user id -- never RevenueCat's own anonymous $RCAnonymousID. Called once at sign-in,
 *  mirroring how the backend's Razorpay integration embeds the raw user id (notes.fynoraUserId)
 *  rather than a separate mapping id. */
export function configureRevenueCat(fynoraUserId: string): void {
  if (configured) return;
  if (!REVENUECAT_API_KEY) {
    throw new Error('EXPO_PUBLIC_REVENUECAT_API_KEY is not set.');
  }
  Purchases.configure({ apiKey: REVENUECAT_API_KEY, appUserID: fynoraUserId });
  configured = true;
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
