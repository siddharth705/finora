import { Link } from 'react-router-dom';
import { PublicLayout, PublicSection } from '../components/PublicLayout';

export default function ShippingPolicy() {
  return (
    <PublicLayout
      title="Shipping Policy"
      subtitle="Last updated: August 2026. Finora is a digital service — no physical goods are shipped."
    >
      <PublicSection title="Digital Delivery Only">
        <p>
          Finora is a software-as-a-service product. Nothing you purchase is a physical item, so there is no
          shipping, packaging, or courier involved at any point.
        </p>
      </PublicSection>

      <PublicSection title="Access Activation">
        <p>
          Paid features are delivered digitally: access to a plan is granted to your account automatically as
          soon as payment succeeds, and removed at the end of your billing cycle if you cancel — see{' '}
          <Link to="/refund-policy" className="text-primary hover:underline">Refund & Cancellation Policy</Link>.
        </p>
      </PublicSection>

      <PublicSection title="No Physical Goods">
        <p>
          Finora does not sell, ship, or deliver any physical product. All statements, reports, and exports you
          generate within the app are downloaded directly by you as digital files (CSV, PDF); nothing is mailed
          or couriered.
        </p>
      </PublicSection>

      <PublicSection title="Questions">
        <p>
          If you have questions about accessing a feature you've paid for, see{' '}
          <Link to="/contact" className="text-primary hover:underline">Contact Us</Link>.
        </p>
      </PublicSection>
    </PublicLayout>
  );
}
