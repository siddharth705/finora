import { Link } from 'react-router-dom';
import { PublicLayout, PublicSection } from '../components/PublicLayout';
import { SUPPORT_EMAIL, SUPPORT_MAILTO } from '../lib/contact';

export default function Terms() {
  return (
    <PublicLayout
      title="Terms & Conditions"
      subtitle="Last updated: July 2026. Please read these terms carefully before using Finora."
    >
      <PublicSection title="1. Acceptance of Terms">
        <p>
          By creating an account or otherwise using Finora ("the Service"), you agree to be bound by these
          Terms & Conditions. If you do not agree to these terms, please do not use the Service.
        </p>
      </PublicSection>

      <PublicSection title="2. User Responsibilities">
        <p>
          You are responsible for the accuracy of the information you provide, including account details,
          transaction data, and any bank or card statements you choose to import. Finora helps you organize
          and understand your own financial data — it does not verify the accuracy of the underlying source
          documents on your behalf.
        </p>
      </PublicSection>

      <PublicSection title="3. Account Registration">
        <p>
          To use Finora, you must register with a valid email address and mobile number. You agree to provide
          accurate, current information and to keep it up to date. You may not register on behalf of someone
          else without their permission, and you may not maintain more than one account per person.
        </p>
      </PublicSection>

      <PublicSection title="4. Account Security">
        <p>
          You are responsible for maintaining the confidentiality of your password and for all activity that
          occurs under your account. Notify us immediately if you suspect unauthorized access. Finora stores
          passwords using industry-standard hashing and never stores them in plain text.
        </p>
      </PublicSection>

      <PublicSection title="5. Acceptable Use">
        <p>You agree not to:</p>
        <ul className="list-disc list-inside space-y-1.5 ml-1">
          <li>Use the Service for any unlawful purpose or in violation of any applicable regulation</li>
          <li>Attempt to gain unauthorized access to another user's account or data</li>
          <li>Upload files containing malicious code or attempt to disrupt the Service's operation</li>
          <li>Reverse-engineer, scrape, or resell access to the Service without written permission</li>
        </ul>
      </PublicSection>

      <PublicSection title="6. Data Processing">
        <p>
          When you import a bank or credit card statement, Finora processes that file to extract transactions,
          detect accounts, and generate categorization suggestions. This processing happens so the Service can
          function — see our <Link to="/privacy" className="text-primary hover:underline">Privacy Policy</Link> for
          full detail on what is collected, how it's stored, and your rights over it.
        </p>
      </PublicSection>

      <PublicSection title="7. Subscription & Billing">
        <p>
          Finora currently offers a Free plan. Premium and Enterprise plans described on our pricing page are
          not yet billable — no payment method is collected, and no charges will occur, until those plans are
          formally launched and clearly communicated in advance.
        </p>
      </PublicSection>

      <PublicSection title="8. Intellectual Property">
        <p>
          The Finora name, logo, and the Service's underlying software are the property of Finora and its
          licensors. Your own financial data — transactions, accounts, budgets, goals, and anything else you
          create or import — remains yours.
        </p>
      </PublicSection>

      <PublicSection title="9. Limitation of Liability">
        <p>
          Finora is a personal finance organization tool, not a bank, financial advisor, or broker. Nothing in
          the Service constitutes financial, investment, tax, or legal advice. Finora is provided "as is,"
          without warranty of any kind, and Finora shall not be liable for any indirect, incidental, or
          consequential damages arising from your use of the Service.
        </p>
      </PublicSection>

      <PublicSection title="10. Account Termination">
        <p>
          You may stop using the Service and request account deletion at any time. Finora may suspend or
          terminate accounts that violate these terms, engage in fraudulent activity, or pose a security risk
          to the Service or other users.
        </p>
      </PublicSection>

      <PublicSection title="11. Governing Law">
        <p>
          These terms are governed by the laws of India, without regard to conflict-of-law principles. Any
          disputes arising from these terms or your use of the Service shall be subject to the exclusive
          jurisdiction of the courts of India.
        </p>
      </PublicSection>

      <PublicSection title="12. Contact Information">
        <p>
          Questions about these terms can be sent to{' '}
          <a href={SUPPORT_MAILTO} className="text-primary hover:underline">{SUPPORT_EMAIL}</a>,
          or see <Link to="/contact" className="text-primary hover:underline">Contact Us</Link>.
        </p>
      </PublicSection>
    </PublicLayout>
  );
}
