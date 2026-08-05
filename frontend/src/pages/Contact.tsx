import { PublicLayout, PublicSection } from '../components/PublicLayout';
import { SUPPORT_EMAIL, SUPPORT_MAILTO } from '../lib/contact';

export default function Contact() {
  return (
    <PublicLayout
      title="Contact Us"
      subtitle="Reach the Finora team for support, billing questions, or anything else."
    >
      <PublicSection title="Support">
        <p>
          For account issues, import problems, or general questions, email{' '}
          <a href={SUPPORT_MAILTO} className="text-primary hover:underline">
            {SUPPORT_EMAIL}
          </a>
          . We aim to respond to every inquiry as quickly as we can.
        </p>
      </PublicSection>

      <PublicSection title="Business Address">
        {/*
          PLACEHOLDER -- fill in with Finora's real registered business address before this page
          goes live. Razorpay's activation review checks that a Contact page shows a real physical
          address; a missing or fake one is a documented reason activation gets held up.
        */}
        <p className="border border-dashed border-amber-500/40 bg-amber-500/10 text-amber-200 rounded-lg px-4 py-3">
          [Add Finora's registered business address here before publishing this page.]
        </p>
      </PublicSection>

      <PublicSection title="Response Time">
        <p>
          We aim to respond to all inquiries as quickly as possible. For urgent account-access issues, include
          "urgent" in your subject line.
        </p>
      </PublicSection>
    </PublicLayout>
  );
}
