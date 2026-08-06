import { Reveal, Section, SectionHeading } from './primitives';
import { DashboardMock } from './DashboardMock';
import { showcase } from './landing-config';

/**
 * The payoff shot. Shown whole and at once -- by this point in the page the argument has been
 * made, and what's left to demonstrate is simply that it all lives in one place.
 */
export function DashboardShowcase() {
  return (
    <Section id="how">
      <SectionHeading
        eyebrow={showcase.eyebrow}
        title={<>{showcase.title}<br />{showcase.titleLine2}</>}
        blurb={showcase.blurb}
      />
      <Reveal><DashboardMock withSidebar /></Reveal>
    </Section>
  );
}
