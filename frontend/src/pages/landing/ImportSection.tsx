import { Eyebrow, Reveal, Section } from './primitives';
import { importSection } from './landing-config';
import { ImportScrollStory } from './import-story/ImportScrollStory';

/**
 * Import, shown as a mechanism rather than described as one.
 *
 * The mechanism is a pinned, GSAP-ScrollTrigger-scrubbed sequence (see
 * docs/superpowers/specs/2026-08-23-scroll-storytelling-design.md) on desktop, and a reveal-once
 * version of the same three beats on mobile/reduced-motion -- both live in ./import-story,
 * rendered here via ImportScrollStory. That whole scene is aria-hidden; the real information
 * ("upload once, everything else is automatic") is this section's own copy below, unchanged and
 * always in normal document flow regardless of animation state.
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
          <ImportScrollStory />
        </Reveal>
      </div>
    </Section>
  );
}
