import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Billing from './Billing';
import { billingApi } from '../api/endpoints';
import { openRazorpayCheckout } from '../lib/razorpayCheckout';
import type { BillingHistoryEntry, MySubscription } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  billingApi: {
    history: vi.fn(), mySubscription: vi.fn(), checkout: vi.fn(), cancel: vi.fn(),
    changePlan: vi.fn(), cancelPendingOrder: vi.fn(),
  },
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
    pendingOrder: null,
    ...overrides,
  };
}

function entry(overrides: Partial<BillingHistoryEntry> = {}): BillingHistoryEntry {
  return {
    id: 'payment-1', amount: 499, currency: 'INR', provider: 'RAZORPAY', status: 'SUCCESS',
    createdAt: '2026-08-20T10:00:00Z', ...overrides,
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
});
