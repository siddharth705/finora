import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Billing from './Billing';
import { billingApi, userApi } from '../api/endpoints';
import { openRazorpayCheckout } from '../lib/razorpayCheckout';
import type { BillingHistoryEntry, MySubscription, UserSettings } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  billingApi: {
    history: vi.fn(), mySubscription: vi.fn(), checkout: vi.fn(), cancel: vi.fn(),
    changePlan: vi.fn(), cancelPendingOrder: vi.fn(),
  },
  userApi: { get: vi.fn() },
}));
vi.mock('../lib/razorpayCheckout', () => ({
  openRazorpayCheckout: vi.fn(),
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <Billing />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function subscription(overrides: Partial<MySubscription> = {}): MySubscription {
  return {
    planCode: 'FREE', planName: 'Free', billingCycle: null, status: 'ACTIVE',
    renewalDate: null, autoRenew: true, hasBillingSubscription: false, pendingChange: null,
    pendingOrder: null, paymentProvider: null,
    ...overrides,
  };
}

function entry(overrides: Partial<BillingHistoryEntry> = {}): BillingHistoryEntry {
  return {
    id: 'payment-1', amount: 499, currency: 'INR', provider: 'RAZORPAY', status: 'SUCCESS',
    createdAt: '2026-08-20T10:00:00Z', ...overrides,
  };
}

function userSettings(overrides: Partial<UserSettings> = {}): UserSettings {
  return {
    email: 'ada@example.com', fullName: 'Ada Lovelace', lowBalanceThreshold: 0, theme: 'system',
    timezone: 'UTC', phoneNumber: '+919876543210', phoneVerified: true, // synthetic-ok
    createdAt: '2026-01-01T00:00:00Z', passwordChangedAt: null, signInMethod: 'PASSWORD',
    ...overrides,
  };
}

describe('Billing', () => {
  beforeEach(() => {
    vi.mocked(billingApi.history).mockReset().mockResolvedValue([]);
    vi.mocked(billingApi.mySubscription).mockReset();
    vi.mocked(billingApi.checkout).mockReset();
    vi.mocked(billingApi.cancel).mockReset();
    vi.mocked(billingApi.changePlan).mockReset();
    vi.mocked(billingApi.cancelPendingOrder).mockReset();
    vi.mocked(openRazorpayCheckout).mockReset();
    vi.mocked(userApi.get).mockReset().mockResolvedValue(userSettings());
  });

  it('shows the current Free plan and no cancel button', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription());
    renderPage();

    expect(await screen.findByText('Free')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /cancel/i })).not.toBeInTheDocument();
  });

  it('shows the renewal date and a cancel button for a paid plan', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      planCode: 'PLUS', planName: 'Plus', billingCycle: 'MONTHLY',
      renewalDate: '2026-11-01', hasBillingSubscription: true,
    }));
    renderPage();

    // { selector: 'p' } disambiguates from the "Choose a plan" dropdown's own "Plus" <option>.
    expect(await screen.findByText('Plus', { selector: 'p' })).toBeInTheDocument();
    // formatDate renders a LocalDate like "2026-11-01" as e.g. "1 Nov 2026" (en-IN,
    // locale-dependent exact token order) -- assert on the parts that don't vary, not the literal
    // ISO string, which never appears in the rendered DOM once formatDate is applied.
    expect(screen.getByText(/nov/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /cancel/i })).toBeInTheDocument();
  });

  it('shows an ends-on message and hides the cancel button once already cancelled', async () => {
    // BillingCheckoutService.cancel() only flips autoRenew -- status/renewalDate/
    // hasBillingSubscription are all untouched until the actual webhook lands (design spec
    // §6.3). The Billing Portal must still tell the user their cancellation took effect.
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      planCode: 'PLUS', planName: 'Plus', billingCycle: 'MONTHLY',
      renewalDate: '2026-11-01', hasBillingSubscription: true, autoRenew: false,
    }));
    renderPage();

    await screen.findByText('Plus', { selector: 'p' });
    expect(screen.getByText(/ends.*won't renew/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^cancel subscription$/i })).not.toBeInTheDocument();
  });

  it('shows a pending downgrade banner', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      planCode: 'PREMIUM', planName: 'Premium', billingCycle: 'MONTHLY',
      hasBillingSubscription: true,
      pendingChange: { toPlanCode: 'PLUS', toPlanName: 'Plus', effectiveAt: '2026-11-01T00:00:00Z' },
    }));
    renderPage();

    expect(await screen.findByText(/downgrading to plus/i)).toBeInTheDocument();
  });

  it('shows a resume/cancel banner for an abandoned checkout', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      pendingOrder: { planCode: 'PREMIUM', planName: 'Premium', billingCycle: 'YEARLY', razorpaySubscriptionId: 'sub_stuck', keyId: 'rzp_test' },
    }));
    renderPage();

    // The fuller phrase disambiguates from the "Choose a plan" dropdown's own "Premium" <option>.
    expect(await screen.findByText(/started upgrading to premium/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /resume checkout/i })).toBeInTheDocument();
  });

  it('resuming a pending order opens Razorpay directly without calling checkout again', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      pendingOrder: { planCode: 'PREMIUM', planName: 'Premium', billingCycle: 'YEARLY', razorpaySubscriptionId: 'sub_stuck', keyId: 'rzp_test' },
    }));
    vi.mocked(openRazorpayCheckout).mockResolvedValue({ paymentId: 'pay_1' });
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('button', { name: /resume checkout/i });

    await user.click(screen.getByRole('button', { name: /resume checkout/i }));

    await waitFor(() => expect(openRazorpayCheckout).toHaveBeenCalledWith(
      expect.objectContaining({ key: 'rzp_test', subscription_id: 'sub_stuck' })
    ));
    expect(billingApi.checkout).not.toHaveBeenCalled();
  });

  it('shows an error if resuming a pending order fails to open Razorpay', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      pendingOrder: { planCode: 'PREMIUM', planName: 'Premium', billingCycle: 'YEARLY', razorpaySubscriptionId: 'sub_stuck', keyId: 'rzp_test' },
    }));
    vi.mocked(openRazorpayCheckout).mockRejectedValue(new Error('Failed to load Razorpay Checkout.'));
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('button', { name: /resume checkout/i });

    await user.click(screen.getByRole('button', { name: /resume checkout/i }));

    expect(await screen.findByText(/could not resume this checkout/i)).toBeInTheDocument();
  });

  it('disables Resume checkout while a resume is already in flight', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      pendingOrder: { planCode: 'PREMIUM', planName: 'Premium', billingCycle: 'YEARLY', razorpaySubscriptionId: 'sub_stuck', keyId: 'rzp_test' },
    }));
    let resolveCheckout: (v: { paymentId: string } | null) => void;
    vi.mocked(openRazorpayCheckout).mockReturnValue(new Promise((resolve) => { resolveCheckout = resolve; }));
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('button', { name: /resume checkout/i });

    await user.click(screen.getByRole('button', { name: /resume checkout/i }));
    // Still in flight (the mocked promise hasn't resolved yet) -- a second click must not open a
    // second Razorpay widget.
    await user.click(screen.getByRole('button', { name: /resume checkout/i }));
    resolveCheckout!({ paymentId: 'pay_1' });

    await waitFor(() => expect(openRazorpayCheckout).toHaveBeenCalledTimes(1));
  });

  it('cancelling a pending order calls cancelPendingOrder after confirmation', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      pendingOrder: { planCode: 'PREMIUM', planName: 'Premium', billingCycle: 'YEARLY', razorpaySubscriptionId: 'sub_stuck', keyId: 'rzp_test' },
    }));
    vi.mocked(billingApi.cancelPendingOrder).mockResolvedValue({ message: 'Cancelled' });
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole('button', { name: /resume checkout/i });

    await user.click(screen.getByRole('button', { name: /^cancel$/i }));
    await user.click(screen.getByRole('button', { name: /confirm/i }));

    await waitFor(() => expect(billingApi.cancelPendingOrder).toHaveBeenCalled());
  });

  it('checking out a paid plan from Free opens the Razorpay widget', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription());
    vi.mocked(billingApi.checkout).mockResolvedValue({ razorpaySubscriptionId: 'sub_new', keyId: 'rzp_test' });
    vi.mocked(openRazorpayCheckout).mockResolvedValue({ paymentId: 'pay_1' });
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('Free');

    await user.selectOptions(screen.getByLabelText(/choose a plan/i), 'PLUS');
    await user.click(screen.getByRole('button', { name: /subscribe/i }));

    await waitFor(() => expect(billingApi.checkout).toHaveBeenCalledWith('PLUS', 'MONTHLY'));
    expect(openRazorpayCheckout).toHaveBeenCalledWith(
      expect.objectContaining({ key: 'rzp_test', subscription_id: 'sub_new' })
    );
  });

  // Bug found in review: openRazorpayCheckout was called with no `prefill` at all, so Razorpay's
  // widget always asked for contact details fresh even though Fynora already has the user's
  // verified email and phone number -- checked against the real code, not assumed.
  it('prefills the Razorpay widget with the signed-in user\'s email, phone, and name', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription());
    vi.mocked(billingApi.checkout).mockResolvedValue({ razorpaySubscriptionId: 'sub_new', keyId: 'rzp_test' });
    vi.mocked(openRazorpayCheckout).mockResolvedValue({ paymentId: 'pay_1' });
    vi.mocked(userApi.get).mockResolvedValue(userSettings({
      email: 'grace@example.com', fullName: 'Grace Hopper', phoneNumber: '+911234567890', // synthetic-ok
    }));
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('Free');

    await user.click(screen.getByRole('button', { name: /subscribe/i }));

    await waitFor(() => expect(openRazorpayCheckout).toHaveBeenCalledWith(
      expect.objectContaining({
        prefill: { email: 'grace@example.com', contact: '+911234567890', name: 'Grace Hopper' }, // synthetic-ok
      })
    ));
  });

  it('omits the phone from prefill when the account has none (e.g. Google sign-in)', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription());
    vi.mocked(billingApi.checkout).mockResolvedValue({ razorpaySubscriptionId: 'sub_new', keyId: 'rzp_test' });
    vi.mocked(openRazorpayCheckout).mockResolvedValue({ paymentId: 'pay_1' });
    vi.mocked(userApi.get).mockResolvedValue(userSettings({ phoneNumber: null }));
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('Free');

    await user.click(screen.getByRole('button', { name: /subscribe/i }));

    await waitFor(() => expect(openRazorpayCheckout).toHaveBeenCalledWith(
      expect.objectContaining({ prefill: expect.not.objectContaining({ contact: expect.anything() }) })
    ));
  });

  it('shows plain user-facing copy, not the raw API instruction, when checkout hits an in-progress-order 409', async () => {
    // BillingCheckoutService.resumableOrderOrGuard's own message is written for an API caller
    // ("Cancel it (POST /api/v1/billing/pending-order/cancel)...") -- shown verbatim to a real
    // user with no working pendingOrder card on screen (e.g. a stale/failed initial
    // mySubscription fetch), it read as an unactionable raw API error. This must never render as-is.
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription());
    vi.mocked(billingApi.checkout).mockRejectedValue({
      response: {
        status: 409,
        data: {
          message: 'You have a checkout already in progress for a different plan. ' +
            'Cancel it (POST /api/v1/billing/pending-order/cancel) before starting a new one.',
        },
      },
    });
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('Free');
    await user.selectOptions(screen.getByLabelText(/choose a plan/i), 'PLUS');

    await user.click(screen.getByRole('button', { name: /subscribe/i }));

    expect(await screen.findByText(/already have a checkout in progress/i)).toBeInTheDocument();
    expect(screen.queryByText(/POST \/api\/v1\/billing\/pending-order\/cancel/i)).not.toBeInTheDocument();
    // Refetches my-subscription so the actionable Resume/Cancel card gets a fresh chance to
    // render, in case the first load was the one that missed it.
    await waitFor(() => expect(billingApi.mySubscription).toHaveBeenCalledTimes(2));
  });

  it('double-clicking Subscribe only checks out once', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription());
    let resolveCheckout: (v: { razorpaySubscriptionId: string; keyId: string }) => void;
    vi.mocked(billingApi.checkout).mockReturnValue(new Promise((resolve) => { resolveCheckout = resolve; }));
    vi.mocked(openRazorpayCheckout).mockResolvedValue({ paymentId: 'pay_1' });
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('Free');
    await user.selectOptions(screen.getByLabelText(/choose a plan/i), 'PLUS');

    await user.click(screen.getByRole('button', { name: /subscribe/i }));
    // billingApi.checkout hasn't resolved yet -- a second click must not fire a second checkout.
    await user.click(screen.getByRole('button', { name: /subscribe/i }));
    resolveCheckout!({ razorpaySubscriptionId: 'sub_new', keyId: 'rzp_test' });

    await waitFor(() => expect(billingApi.checkout).toHaveBeenCalledTimes(1));
  });

  it('cancelling calls the cancel endpoint after confirmation', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      planCode: 'PLUS', planName: 'Plus', billingCycle: 'MONTHLY', hasBillingSubscription: true,
    }));
    vi.mocked(billingApi.cancel).mockResolvedValue({ message: 'Cancelled' });
    const user = userEvent.setup();
    renderPage();
    await screen.findByText('Plus', { selector: 'p' });

    await user.click(screen.getByRole('button', { name: /cancel/i }));
    await user.click(screen.getByRole('button', { name: /confirm/i }));

    await waitFor(() => expect(billingApi.cancel).toHaveBeenCalled());
  });

  it('renders payment history below the plan card', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription());
    vi.mocked(billingApi.history).mockResolvedValue([entry()]);
    renderPage();

    expect(await screen.findByText('₹499')).toBeInTheDocument();
  });

  it('shows disabled plan controls with a store-managed note for a RevenueCat-owned subscription', async () => {
    vi.mocked(billingApi.mySubscription).mockResolvedValue(subscription({
      planCode: 'PREMIUM', planName: 'Premium', billingCycle: 'MONTHLY',
      renewalDate: '2026-10-06', autoRenew: true, hasBillingSubscription: true,
      paymentProvider: 'REVENUECAT',
    }));
    renderPage();

    expect(await screen.findByText(/managed through the App Store\/Play Store/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /subscribe/i })).toBeDisabled();
    expect(screen.queryByRole('button', { name: /cancel subscription/i })).not.toBeInTheDocument();
  });
});
