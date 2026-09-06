// Razorpay's own hosted Checkout widget (design spec §6.1/§6.5) -- loaded once at runtime, the
// same pattern lib/googleIdentity.ts already uses for Google Identity Services, not an npm
// dependency: Razorpay explicitly documents this script as the integration point, not an SDK
// package to bundle.

export interface RazorpayCheckoutOptions {
  key: string;
  subscription_id: string;
  name: string;
  description: string;
  // Left empty/undefined by every caller here -- Razorpay's own state machine drives entitlement
  // activation via the backend webhook (design spec §6.1 step 6), never this callback's own
  // presence. Declared for completeness against Razorpay's real options shape, not because this
  // app reads it.
  handler?: (response: { razorpay_payment_id: string }) => void;
  modal?: { ondismiss?: () => void };
  // `name` here is the CUSTOMER's name, distinct from the top-level `name` above (the merchant
  // name shown in the widget header, always "Fynora"). `contact` is Razorpay's own field for a
  // prefilled phone number -- bug found in review: this was missing entirely, so Fynora's
  // already-known, OTP-verified phone number never reached the widget.
  prefill?: { email?: string; contact?: string; name?: string };
  theme?: { color?: string };
}

interface RazorpayInstance {
  open(): void;
  on(event: 'payment.failed', handler: (response: { error: { description: string } }) => void): void;
}

type RazorpayConstructor = new (options: RazorpayCheckoutOptions) => RazorpayInstance;

declare global {
  interface Window {
    Razorpay?: RazorpayConstructor;
  }
}

const SCRIPT_SRC = 'https://checkout.razorpay.com/v1/checkout.js';

let scriptPromise: Promise<RazorpayConstructor> | null = null;

/**
 * Loads Razorpay's Checkout script exactly once (cached across every call), resolving once
 * `window.Razorpay` is actually usable. Rejects rather than hanging forever on a load failure
 * (offline, an ad blocker, Razorpay's CDN unreachable) -- mirrors
 * lib/googleIdentity.ts's loadGoogleIdentityServices exactly, including not caching a failure so a
 * later retry gets a fresh attempt.
 */
export function loadRazorpayCheckout(): Promise<RazorpayConstructor> {
  if (window.Razorpay) {
    return Promise.resolve(window.Razorpay);
  }
  if (!scriptPromise) {
    scriptPromise = new Promise<RazorpayConstructor>((resolve, reject) => {
      const existing = document.querySelector<HTMLScriptElement>(`script[src="${SCRIPT_SRC}"]`);
      const script = existing ?? document.createElement('script');
      script.addEventListener('load', () => {
        if (window.Razorpay) resolve(window.Razorpay);
        else reject(new Error('Razorpay Checkout loaded but window.Razorpay is missing.'));
      });
      script.addEventListener('error', () => reject(new Error('Failed to load Razorpay Checkout.')));
      if (!existing) {
        script.src = SCRIPT_SRC;
        script.async = true;
        document.head.appendChild(script);
      }
    }).catch((err) => {
      // Don't cache a failure -- a later retry (e.g. the user's connection recovers) should get a
      // fresh attempt rather than being stuck with the first failed load forever.
      scriptPromise = null;
      throw err;
    });
  }
  return scriptPromise!;
}

/**
 * Opens the Checkout widget for a subscription id the backend already created (design spec
 * §6.1 step 3 / §6.5 step 1) and resolves once the user either completes or abandons it.
 *
 * Resolves `{ paymentId }` on Razorpay's own success callback and `null` on dismiss/failure --
 * the caller must NOT treat a non-null resolution as "the plan is now active." Activation only
 * ever happens from the backend's verified webhook (design spec §6.1 step 6, restated for
 * upgrades in §6.5 step 3); this promise resolving is "the checkout flow is over," nothing more.
 */
export async function openRazorpayCheckout(
  options: Omit<RazorpayCheckoutOptions, 'handler' | 'modal'>
): Promise<{ paymentId: string } | null> {
  const Razorpay = await loadRazorpayCheckout();
  return new Promise((resolve) => {
    const instance = new Razorpay({
      ...options,
      handler: (response) => resolve({ paymentId: response.razorpay_payment_id }),
      modal: { ondismiss: () => resolve(null) },
    });
    instance.on('payment.failed', () => resolve(null));
    instance.open();
  });
}
