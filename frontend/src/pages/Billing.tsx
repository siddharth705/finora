import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Receipt, CreditCard } from 'lucide-react';
import { billingApi } from '../api/endpoints';
import { openRazorpayCheckout } from '../lib/razorpayCheckout';
import { formatDate } from '../utils/date';
import { FinoraCard, EmptyState, Button, ConfirmDialog } from '../design-system';

function fmt(amount: number, currency: string) {
  const symbol = currency === 'INR' ? '₹' : currency + ' ';
  return symbol + Math.round(amount).toLocaleString('en-IN');
}

function statusLabel(status: string) {
  switch (status) {
    case 'SUCCESS': return { text: 'Paid', className: 'text-success bg-success-bg' };
    case 'REFUNDED': return { text: 'Refunded', className: 'text-muted bg-bg' };
    case 'FAILED': return { text: 'Failed', className: 'text-danger bg-danger-bg' };
    default: return { text: 'Pending', className: 'text-warning bg-warning-bg' };
  }
}

// Design spec §2's pricing table -- same four numbers Pricing.tsx (public site) and
// V154__subscription_billing_v1.sql (backend seed) both already carry. Duplicated here rather
// than fetched: there is no live pricing-list endpoint (§8 never asks for one), and this fixed,
// rarely-changing catalog matches how frontend/src/pages/landing/plans.ts already hardcodes Free's
// price the same way.
const CHECKOUT_PLANS = [
  { code: 'PLUS', name: 'Plus' },
  { code: 'PREMIUM', name: 'Premium' },
] as const;
const CHECKOUT_CYCLES = [
  { code: 'MONTHLY', label: 'Monthly' },
  { code: 'YEARLY', label: 'Yearly' },
] as const;
const TIER_RANK: Record<string, number> = { FREE: 0, PLUS: 1, PREMIUM: 2 };

/** Polls `mySubscription` after a successful checkout until the plan actually flips, or 30
 *  seconds pass -- design spec §6.1 step 6 / §6.5 step 3: activation only ever comes from the
 *  backend's verified webhook, never from Checkout's own success callback, so this page cannot
 *  just trust that callback and must wait to see the real state change. */
function useActivationPoll(expectedPlanCode: string | null, onSettled: () => void) {
  useEffect(() => {
    if (!expectedPlanCode) return;
    const deadline = Date.now() + 30_000;
    const interval = setInterval(async () => {
      const current = await billingApi.mySubscription();
      if (current.planCode === expectedPlanCode || Date.now() > deadline) {
        clearInterval(interval);
        onSettled();
      }
    }, 2000);
    return () => clearInterval(interval);
     
    // closure from the caller; including it would re-run this effect (and restart the poll) every
    // render, which the caller's re-render-per-tick already causes without help from this list.
  }, [expectedPlanCode]);
}

export default function Billing() {
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [confirmingCancel, setConfirmingCancel] = useState(false);
  const [confirmingCancelPendingOrder, setConfirmingCancelPendingOrder] = useState(false);
  const [targetPlan, setTargetPlan] = useState('PLUS');
  const [targetCycle, setTargetCycle] = useState('MONTHLY');
  const [activatingPlanCode, setActivatingPlanCode] = useState<string | null>(null);
  // Guards both subscribeToPlan and resumePendingOrder against a double-click opening two
  // Razorpay widgets for the same checkout -- neither is a useMutation (each branches on live
  // subscription state read at click time, not a single fixed request), so this is tracked by
  // hand instead of read off a mutation's own .isPending.
  const [isSubmitting, setIsSubmitting] = useState(false);

  const { data: subscription, isLoading: subLoading } = useQuery({
    queryKey: ['my-subscription'],
    queryFn: () => billingApi.mySubscription(),
  });
  const { data: entries, isLoading: historyLoading } = useQuery({
    queryKey: ['billing-history'],
    queryFn: () => billingApi.history(),
  });

  useActivationPoll(activatingPlanCode, () => {
    setActivatingPlanCode(null);
    void queryClient.invalidateQueries({ queryKey: ['my-subscription'] });
    void queryClient.invalidateQueries({ queryKey: ['entitlements'] });
  });

  const cancelMutation = useMutation({
    mutationFn: () => billingApi.cancel(),
    onSuccess: () => {
      setConfirmingCancel(false);
      void queryClient.invalidateQueries({ queryKey: ['my-subscription'] });
    },
    onError: (e: any) => {
      setConfirmingCancel(false);
      setError(e.response?.data?.message ?? 'Could not cancel this subscription. Try again.');
    },
  });

  const cancelPendingOrderMutation = useMutation({
    mutationFn: () => billingApi.cancelPendingOrder(),
    onSuccess: () => {
      setConfirmingCancelPendingOrder(false);
      void queryClient.invalidateQueries({ queryKey: ['my-subscription'] });
    },
    onError: (e: any) => {
      setConfirmingCancelPendingOrder(false);
      setError(e.response?.data?.message ?? 'Could not cancel this pending checkout. Try again.');
    },
  });

  // Plan 3 review. Deliberately does NOT call billingApi.checkout() again -- the whole point of
  // resuming is reusing the SAME Razorpay subscription the abandoned attempt already created, not
  // creating a second one. Wrapped in the same try/catch/isSubmitting-guard discipline as
  // subscribeToPlan below -- this used to have neither, so a Razorpay Checkout load failure (the
  // script blocked, offline) failed silently with no error shown, and a double-click could open
  // two Checkout widgets for the same subscription.
  async function resumePendingOrder() {
    if (!subscription?.pendingOrder || isSubmitting) return;
    setError(null);
    setIsSubmitting(true);
    try {
      const result = await openRazorpayCheckout({
        key: subscription.pendingOrder.keyId,
        subscription_id: subscription.pendingOrder.razorpaySubscriptionId,
        name: 'Fynora',
        description: `${subscription.pendingOrder.planCode} — ${subscription.pendingOrder.billingCycle}`,
      });
      if (result) setActivatingPlanCode(subscription.pendingOrder.planCode);
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not resume this checkout. Try again.');
    } finally {
      setIsSubmitting(false);
    }
  }

  async function subscribeToPlan() {
    if (isSubmitting) return;
    setError(null);
    setIsSubmitting(true);
    try {
      if (!subscription?.hasBillingSubscription) {
        const checkout = await billingApi.checkout(targetPlan, targetCycle);
        const result = await openRazorpayCheckout({
          key: checkout.keyId,
          subscription_id: checkout.razorpaySubscriptionId,
          name: 'Fynora',
          description: `${targetPlan} — ${targetCycle}`,
        });
        if (result) setActivatingPlanCode(targetPlan);
        return;
      }
      const isUpgrade = TIER_RANK[targetPlan] > TIER_RANK[subscription.planCode];
      const checkout = await billingApi.changePlan(targetPlan, targetCycle);
      if (isUpgrade && checkout) {
        const result = await openRazorpayCheckout({
          key: checkout.keyId,
          subscription_id: checkout.razorpaySubscriptionId,
          name: 'Fynora',
          description: `${targetPlan} — ${targetCycle}`,
        });
        if (result) setActivatingPlanCode(targetPlan);
      } else {
        void queryClient.invalidateQueries({ queryKey: ['my-subscription'] });
      }
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not change your plan. Try again.');
    } finally {
      setIsSubmitting(false);
    }
  }

  if (subLoading || historyLoading) return <p className="text-muted">Loading…</p>;

  const payments = entries ?? [];

  return (
    <div className="space-y-6">
      <div className="mb-2">
        <h1 className="text-xl font-bold text-ink">Billing</h1>
        <p className="text-sm text-muted">Your plan, payments, and subscription actions.</p>
      </div>

      {error && (
        <div className="text-sm text-danger bg-danger-bg rounded-lg px-4 py-2.5">{error}</div>
      )}

      {activatingPlanCode && (
        <div className="text-sm text-ink bg-bg border border-border rounded-lg px-4 py-2.5">
          Activating your {activatingPlanCode} plan… this can take a few seconds.
        </div>
      )}

      {subscription?.pendingOrder && (
        <FinoraCard padding="lg">
          <div className="flex items-center justify-between gap-4 flex-wrap">
            <div>
              <p className="text-sm font-semibold text-ink">
                You started upgrading to {subscription.pendingOrder.planName} but didn't finish payment.
              </p>
              <p className="text-xs text-muted mt-0.5">{subscription.pendingOrder.billingCycle} billing</p>
            </div>
            <div className="flex gap-2">
              <Button size="sm" onClick={resumePendingOrder} disabled={isSubmitting || !!activatingPlanCode}>
                Resume checkout
              </Button>
              <Button
                variant="secondary" size="sm" disabled={isSubmitting || !!activatingPlanCode}
                onClick={() => setConfirmingCancelPendingOrder(true)}
              >
                Cancel
              </Button>
            </div>
          </div>
        </FinoraCard>
      )}

      {subscription && (
        <FinoraCard padding="lg">
          <div className="flex items-start justify-between gap-4 flex-wrap">
            <div>
              <p className="text-xs text-muted uppercase tracking-wide mb-1">Current plan</p>
              <p className="text-lg font-bold text-ink">{subscription.planName}</p>
              {subscription.renewalDate && (
                // Cancelling (BillingCheckoutService.cancel) only flips autoRenew -- status and
                // renewalDate are untouched until the actual subscription.cancelled webhook lands
                // (design spec §6.3, "access continues untouched"). Without reading autoRenew
                // here, a user who already cancelled saw the exact same "Renews <date>" text as
                // someone who hadn't, with no sign their cancellation took effect.
                <p className="text-sm text-muted mt-1">
                  {subscription.hasBillingSubscription && !subscription.autoRenew
                    ? <>Ends {formatDate(subscription.renewalDate)} — won't renew</>
                    : <>Renews {formatDate(subscription.renewalDate)}</>}
                </p>
              )}
              {subscription.pendingChange && (
                <p className="text-sm text-warning mt-1">
                  Downgrading to {subscription.pendingChange.toPlanName} on{' '}
                  {formatDate(subscription.pendingChange.effectiveAt)}
                </p>
              )}
            </div>
            {subscription.hasBillingSubscription && subscription.autoRenew && (
              <Button variant="danger" size="sm" onClick={() => setConfirmingCancel(true)}>
                Cancel subscription
              </Button>
            )}
          </div>

          <div className="mt-5 pt-5 border-t border-border flex items-end gap-3 flex-wrap">
            <label className="flex flex-col gap-1 text-xs text-muted">
              Choose a plan
              <select
                value={targetPlan}
                onChange={(e) => setTargetPlan(e.target.value)}
                className="text-sm border border-border rounded-lg px-2.5 py-2 bg-card text-ink"
              >
                {CHECKOUT_PLANS.map((p) => (
                  <option key={p.code} value={p.code}>{p.name}</option>
                ))}
              </select>
            </label>
            <label className="flex flex-col gap-1 text-xs text-muted">
              Billing cycle
              <select
                value={targetCycle}
                onChange={(e) => setTargetCycle(e.target.value)}
                className="text-sm border border-border rounded-lg px-2.5 py-2 bg-card text-ink"
              >
                {CHECKOUT_CYCLES.map((c) => (
                  <option key={c.code} value={c.code}>{c.label}</option>
                ))}
              </select>
            </label>
            <Button
              onClick={subscribeToPlan}
              disabled={isSubmitting || !!activatingPlanCode ||
                (targetPlan === subscription.planCode && targetCycle === subscription.billingCycle)}
            >
              <CreditCard size={14} /> Subscribe
            </Button>
          </div>
        </FinoraCard>
      )}

      {confirmingCancel && (
        <ConfirmDialog
          title="Cancel subscription?"
          message="Your plan stays active until the end of the current billing period, then moves to Free."
          confirmLabel="Confirm"
          danger
          busy={cancelMutation.isPending}
          onConfirm={() => cancelMutation.mutate()}
          onCancel={() => setConfirmingCancel(false)}
        />
      )}

      {confirmingCancelPendingOrder && (
        <ConfirmDialog
          title="Cancel this pending checkout?"
          message="You'll be able to start a fresh checkout for any plan afterward."
          confirmLabel="Confirm"
          danger
          busy={cancelPendingOrderMutation.isPending}
          onConfirm={() => cancelPendingOrderMutation.mutate()}
          onCancel={() => setConfirmingCancelPendingOrder(false)}
        />
      )}

      <div>
        <h2 className="text-sm font-semibold text-ink mb-2">Payment history</h2>
        {payments.length === 0 ? (
          <FinoraCard padding="lg">
            <EmptyState
              icon={Receipt}
              iconBg="bg-blue-100"
              iconColor="text-blue-600"
              title="No billing history yet"
              desc="Payment records will appear here once you've made your first payment."
            />
          </FinoraCard>
        ) : (
          <div className="bg-card rounded-xl2 shadow-card border border-border overflow-hidden">
            <div className="divide-y divide-border">
              {payments.map((p) => {
                const status = statusLabel(p.status);
                return (
                  <div key={p.id} className="px-5 py-3.5 flex items-center justify-between gap-4 flex-wrap">
                    <div className="min-w-0">
                      <p className="text-sm font-medium text-ink">{fmt(p.amount, p.currency)}</p>
                      <p className="text-xs text-muted">
                        {formatDate(p.createdAt)}{p.provider ? ` · ${p.provider}` : ''}
                      </p>
                    </div>
                    <span className={`text-[10px] uppercase font-semibold rounded px-2 py-1 ${status.className}`}>
                      {status.text}
                    </span>
                  </div>
                );
              })}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
