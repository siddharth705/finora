import { Check } from 'lucide-react';
import { Section, SectionHeading, useStagedReveal } from './primitives';
import { learning } from './landing-config';

/**
 * The learning loop, played rather than diagrammed.
 *
 * Five beats: the import guesses, the guess is wrong, you fix it, Finora records the preference,
 * the next import is already right. A static before/after states the same thing but leaves the
 * reader to work out the causality; watching the green check land does it for them.
 *
 * This mirrors a real capability. A corrected merchant is remembered and applied on later imports
 * -- it is not a roadmap animation.
 */
const STAGES = [
  { caption: 'First import', merchant: 'Amazon', tag: 'Uncategorized', tone: 'unknown' as const },
  { caption: 'You fix it', merchant: 'Amazon', tag: 'Shopping', tone: 'edit' as const },
  { caption: 'Finora records it', merchant: 'Pattern saved', tag: null, tone: 'learn' as const },
  { caption: 'Next import', merchant: 'Amazon', tag: 'Shopping', tone: 'done' as const },
];

const TAG_STYLE: Record<string, { background: string; color: string }> = {
  unknown: { background: 'rgb(148 163 184 / .18)', color: '#CBD5E1' },
  // Brand-tinted, but this sits on the dark deep-band surface -- a dark-graphite tint would
  // vanish here, so it borrows the paper tone instead, same as the section's own radial wash.
  edit: { background: 'rgb(244 241 236 / .16)', color: '#F4F1EC' },
  done: { background: 'rgb(22 163 74 / .18)', color: '#4ADE80' },
};

export function LearningSection() {
  const { ref, step } = useStagedReveal(STAGES.length, 700);

  return (
    <Section tone="deep">
      <SectionHeading
        invert
        eyebrow={learning.eyebrow}
        title={<>{learning.title}<br />{learning.titleLine2}</>}
        blurb={learning.blurb}
      />
      <div ref={ref} className="grid sm:grid-cols-2 lg:grid-cols-4 gap-3">
        {STAGES.map((s, i) => {
          const active = step >= i + 1;
          const isDone = s.tone === 'done';
          return (
            <div
              key={s.caption}
              className="rounded-xl p-4 border transition-all duration-500 relative"
              style={{
                background: 'rgb(255 255 255 / .05)',
                borderColor: active && isDone
                  ? 'rgb(22 163 74 / .55)'
                  : active ? 'rgb(244 241 236 / .45)' : 'rgb(255 255 255 / .10)',
                opacity: active ? 1 : 0.4,
                transform: active ? 'none' : 'translateY(8px)',
              }}
            >
              <p className="text-[10px] uppercase tracking-wide text-slate-400 mb-2">{s.caption}</p>
              <p className="text-sm font-semibold text-slate-100 mb-2">{s.merchant}</p>
              {s.tag ? (
                <span className="inline-flex items-center gap-1 text-[10px] font-medium px-2 py-1 rounded-md" style={TAG_STYLE[s.tone]}>
                  {isDone && active ? <Check size={11} /> : null}
                  {s.tag}
                </span>
              ) : (
                <span className="inline-block text-[10px] px-2 py-1 rounded-md" style={TAG_STYLE.edit}>
                  learning…
                </span>
              )}
            </div>
          );
        })}
      </div>
      <p className="text-center text-sm mt-8 text-slate-400">
        {learning.footnote}
      </p>
    </Section>
  );
}
