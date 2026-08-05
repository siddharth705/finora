import { Link } from 'react-router-dom';
import { PublicLayout, PublicSection } from '../components/PublicLayout';
import { SUPPORT_EMAIL, SUPPORT_MAILTO } from '../lib/contact';

export default function RefundPolicy() {
  return (
    <PublicLayout
      title="Refund & Cancellation Policy"
      subtitle="Last updated: August 2026. Applies to any paid Finora subscription."
    >
      <PublicSection title="Current Billing Status">
        <p>
          Finora currently offers a Free plan only. No payment method is collected and no charges occur today.
          This policy describes the terms that will govern billing once paid plans are formally launched — see{' '}
          <Link to="/terms" className="text-primary hover:underline">Terms & Conditions</Link> for the full
          subscription clause.
        </p>
      </PublicSection>

      <PublicSection title="Cancelling a Subscription">
        <p>
          You may cancel a paid subscription at any time from within the app. Cancellation takes effect at the
          end of your current billing cycle — you keep full access to paid features until then, and you will not
          be charged again after that cycle ends.
        </p>
      </PublicSection>

      <PublicSection title="Refunds">
        <p>
          Finora does not offer refunds for partial billing periods or unused time within a cycle you've already
          paid for. If you cancel partway through a cycle, you retain access until the cycle ends rather than
          receiving a prorated refund.
        </p>
      </PublicSection>

      <PublicSection title="Billing Errors">
        <p>
          If you're charged in error — a duplicate charge, a charge after you cancelled, or an incorrect amount —
          contact us and we will investigate and correct it, including a refund where the error is confirmed on
          our side.
        </p>
      </PublicSection>

      <PublicSection title="Free Plan">
        <p>
          The Free plan is not billed, so there is nothing to cancel or refund on it. Downgrading from a paid
          plan to Free follows the same end-of-cycle timing described above.
        </p>
      </PublicSection>

      <PublicSection title="How to Cancel">
        <p>
          Cancel from Settings within the app, or email{' '}
          <a href={SUPPORT_MAILTO} className="text-primary hover:underline">
            {SUPPORT_EMAIL}
          </a>{' '}
          and we'll process it for you.
        </p>
      </PublicSection>

      <PublicSection title="Contact">
        <p>
          Questions about a charge or this policy: see{' '}
          <Link to="/contact" className="text-primary hover:underline">Contact Us</Link>.
        </p>
      </PublicSection>
    </PublicLayout>
  );
}
