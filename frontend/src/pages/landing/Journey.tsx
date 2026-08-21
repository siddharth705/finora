import { DashboardMock } from './DashboardMock';
import { Reveal, Section, SectionHeading, useStagedReveal } from './primitives';
import { journey } from './landing-config';

/**
 * The timeline, told as change rather than as months.
 *
 * "Jan, Feb, Mar" is a calendar and says nothing. "In January you tracked spending; by December
 * you understand your money" is the actual promise of the product, and it is the one section
 * where the value is cumulative -- it only exists after several months of use, so it has to be
 * described rather than screenshotted.
 *
 * Worth being careful here: these are outcomes of using the product, not guarantees. The wording
 * stays in the second person about what becomes visible, never a claim about how much a user will
 * save -- that would be a financial promise nothing in the codebase can keep.
 */
export function Journey() {
  const { ref, step } = useStagedReveal(journey.milestones.length, 420);

  return (
    <Section>
      <SectionHeading
        eyebrow={journey.eyebrow}
        title={<>{journey.title}<br />{journey.titleLine2}</>}
        blurb={journey.blurb}
      />

      <div ref={ref} className="max-w-3xl mx-auto">
        {journey.milestones.map((m, i) => {
          const active = step >= i + 1;
          const last = i === journey.milestones.length - 1;
          return (
            <div key={m.month} className="grid grid-cols-[auto_1fr] gap-5">
              {/* Rail: the dot marks the month, the line carries the eye to the next one. */}
              <div className="flex flex-col items-center">
                <span
                  className="w-3.5 h-3.5 rounded-full border-2 transition-all duration-500 shrink-0"
                  style={{
                    borderColor: active ? 'var(--m-brand)' : '#CBD5E1',
                    background: active ? 'var(--m-brand)' : '#fff',
                    transform: active ? 'scale(1)' : 'scale(.7)',
                  }}
                />
                {!last ? (
                  <span
                    className="w-[2px] flex-1 min-h-[72px] transition-colors duration-700"
                    style={{ background: active ? '#BFDBFE' : '#E6EAF2' }}
                  />
                ) : null}
              </div>

              <div
                className="pb-10 transition-all duration-500"
                style={{ opacity: active ? 1 : 0.35, transform: active ? 'none' : 'translateY(6px)' }}
              >
                <p className="m-eyebrow mb-1">{m.month}</p>
                <h3 className="m-h3 text-xl mb-1.5">{m.headline}</h3>
                <p className="text-[15px] leading-relaxed" style={{ color: 'var(--m-ink-2)' }}>{m.body}</p>
              </div>
            </div>
          );
        })}
      </div>

      <Reveal delayMs={160}>
        <div className="flex flex-wrap justify-center gap-2.5 mt-2 mb-12">
          {journey.outcomes.map((o) => (
            <span key={o} className="m-node" style={{ borderColor: 'rgb(38 42 51 / .14)', background: 'var(--m-brand-wash)', color: 'var(--m-brand-deep)' }}>
              {o}
            </span>
          ))}
        </div>
      </Reveal>

      {/* The middle depth of the three. The hero showed the headline numbers; a year in, the same
          dashboard can say where the money actually went. Placed here because this section's whole
          claim is accumulation -- showing it is cheaper than asserting it. */}
      <Reveal delayMs={220}>
        <div className="max-w-3xl mx-auto">
          <DashboardMock level="expanded" />
        </div>
      </Reveal>
    </Section>
  );
}
