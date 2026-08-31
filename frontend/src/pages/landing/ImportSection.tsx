import { Eyebrow, Reveal, Section } from './primitives';
import { importSection } from './landing-config';
import { ImportRevealSequence } from './import-story/ImportRevealSequence';

/**
 * Import, shown as a mechanism rather than described as one.
 *
 * The mechanism is ImportRevealSequence's reveal-once sequence (documents -> processing ->
 * insights), played once as this section scrolls into view -- no pinning, no scroll-scrub. That
 * scene is aria-hidden; the real information ("upload once, everything else is automatic") is
 * this section's own copy, always in normal document flow regardless of animation state.
 *
 * This used to be a pinned, GSAP-ScrollTrigger-scrubbed sequence on desktop (mobile/reduced-motion
 * already used ImportRevealSequence as a fallback -- see its own doc comment). Dropped after real
 * user feedback that pinning the page for ~2.5 screen-heights of scroll to watch three beats felt
 * bad regardless of how short the distance was tuned to -- not a length problem to tune away, a
 * mechanic problem. ImportRevealSequence is now the only version, for every visitor.
 *
 * Every format listed is genuinely supported today -- password-protected PDFs and multi-account
 * composite statements included. Nothing aspirational in this list.
 */
export function ImportSection() {
  return (
    <Section id="import">
      <div className="grid lg:grid-cols-2 gap-14 items-center">
        <Reveal>
          <Eyebrow>{importSection.eyebrow}</Eyebrow>
          <h2 className="m-h2 mb-4">{importSection.title}<br />{importSection.titleLine2}</h2>
          <p className="m-lead mb-6">{importSection.blurb}</p>
          <div className="flex flex-wrap gap-2">
            {importSection.supported.map((s) => (
              <span key={s} className="text-xs font-medium px-3 py-1.5 rounded-full" style={{ background: 'var(--m-brand-wash)', color: 'var(--m-brand-deep)' }}>
                {s}
              </span>
            ))}
          </div>
        </Reveal>

        <Reveal delayMs={120}>
          <ImportRevealSequence />
        </Reveal>
      </div>
    </Section>
  );
}
