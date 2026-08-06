import { Reveal, Section, SectionHeading } from './primitives';
import { DashboardMock } from './DashboardMock';

/**
 * The payoff shot. Shown whole and at once -- by this point in the page the argument has been
 * made, and what's left to demonstrate is simply that it all lives in one place.
 */
export function DashboardShowcase() {
  return (
    <Section id="how">
      <SectionHeading
        eyebrow="See it in action"
        title={<>Everything.<br />In one place.</>}
        blurb="Accounts, transactions, budgets, goals, reports and insights — one picture instead of six tabs."
      />
      <Reveal><DashboardMock withSidebar /></Reveal>
    </Section>
  );
}
