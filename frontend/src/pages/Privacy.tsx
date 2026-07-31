import { PublicLayout, PublicSection } from '../components/PublicLayout';

export default function Privacy() {
  return (
    <PublicLayout
      title="Privacy Policy"
      subtitle="Last updated: July 2026. This explains what Finora collects, why, and the rights you have over it."
    >
      <PublicSection title="Information We Collect">
        <p>
          Finora collects the information you provide directly (registration details, account and transaction
          data you add or import) and a small amount of technical information needed to operate the Service
          securely (login timestamps, IP address at login, device/browser information for session security).
        </p>
      </PublicSection>

      <PublicSection title="Personal Information">
        <p>
          This includes your full name, email address, and mobile number, collected at registration and used
          for authentication (including email-or-phone login), account recovery, and OTP-based phone
          verification.
        </p>
      </PublicSection>

      <PublicSection title="Financial Data">
        <p>
          Finora stores the accounts, transactions, budgets, goals, and categorization data you create or
          import. This data is used exclusively to power the features you use — dashboards, reports, budgets,
          and insights — and is never sold to third parties or used for advertising.
        </p>
      </PublicSection>

      <PublicSection title="Uploaded Statements">
        <p>
          When you import a bank or credit card statement, the original file is stored securely and linked to
          your account so you can re-download it or re-process it later from Statement History. Only you can
          access your uploaded statements.
        </p>
      </PublicSection>

      <PublicSection title="Cookies">
        <p>
          Finora uses essential, session-related storage (such as your authentication token) to keep you
          signed in. We do not currently use third-party advertising or tracking cookies.
        </p>
      </PublicSection>

      <PublicSection title="Analytics">
        <p>
          We may collect aggregated, non-identifying usage data (such as which features are used most) to
          improve the product. This is never combined with your individual financial data for any purpose
          outside operating and improving Finora itself.
        </p>
      </PublicSection>

      <PublicSection title="Data Usage">
        <p>
          Your data is used to: provide the core features you sign up for; generate categorization suggestions
          and AI insights; detect duplicate or transfer transactions; and secure your account (fraud/lockout
          detection on repeated failed logins).
        </p>
      </PublicSection>

      <PublicSection title="Data Encryption">
        <p>
          Passwords are hashed with bcrypt and never stored or logged in plain text. Password reset tokens are
          hashed before storage, so a database compromise alone cannot be used to reset an account. All traffic
          between your browser and Finora's servers is encrypted in transit (HTTPS).
        </p>
      </PublicSection>

      <PublicSection title="Data Retention">
        <p>
          Your data is retained for as long as your account is active. Deleted accounts, transactions, and
          statements are soft-deleted first (recoverable for a short window — e.g. a deleted statement's
          associated account remains visible in Statement History for 7 days) before being permanently removed.
        </p>
      </PublicSection>

      <PublicSection title="User Rights">
        <p>
          You have the right to access, correct, or delete your personal and financial data at any time from
          within the app (Settings, Accounts, Transactions) or by contacting us directly.
        </p>
      </PublicSection>

      <PublicSection title="Data Deletion">
        <p>
          You may delete individual transactions, accounts, or statements from within Finora, or request full
          account deletion by contacting{' '}
          <a href="mailto:support@finora.app" className="text-primary hover:underline">support@finora.app</a>.
          Full account deletion removes your personal information and financial data from active systems.
        </p>
      </PublicSection>

      <PublicSection title="Third-Party Services">
        <p>
          Finora does not sell your data to third parties. Where a third-party service is used (such as an
          email or SMS provider to deliver password reset links or OTP codes), only the minimum information
          needed to deliver that message is shared.
        </p>
      </PublicSection>

      <PublicSection title="Policy Updates">
        <p>
          We may update this policy from time to time. Material changes will be reflected by an updated "Last
          updated" date at the top of this page.
        </p>
      </PublicSection>
    </PublicLayout>
  );
}
