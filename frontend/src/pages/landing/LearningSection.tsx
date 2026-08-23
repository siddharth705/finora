import { useRef } from 'react';
import { Check } from 'lucide-react';
import { useReducedMotion } from 'framer-motion';
import { Section, SectionHeading } from './primitives';
import { learning } from './landing-config';
import { useLearningTimeline } from './useLearningTimeline';

/**
 * The learning loop, played rather than diagrammed.
 *
 * Five beats: the import guesses, the guess is wrong, you fix it, Finora records the preference,
 * the next import is already right. A static before/after states the same thing but leaves the
 * reader to work out the causality; watching the connector draw from card to card and the final
 * "Pattern confirmed" pulse land does it for them.
 *
 * This mirrors a real capability. A corrected merchant is remembered and applied on later imports
 * -- it is not a roadmap animation. See docs/superpowers/specs/2026-08-23-learning-section-
 * animation-design.md for the full design, including why this stays a play-once sequence (no
 * pin, no scrub -- see ImportSection for that register) and why the confirmation line reads
 * "Pattern confirmed" rather than a literal confidence percentage.
 */
const STAGES = [
  { caption: 'First import', merchant: 'Amazon', tag: 'Uncategorized', tone: 'unknown' as const },
  { caption: 'You fix it', merchant: 'Amazon', tag: 'Shopping', tone: 'edit' as const },
  { caption: 'Finora records it', merchant: 'Pattern saved', tag: null, tone: 'learn' as const },
  { caption: 'Next import', merchant: 'Amazon', tag: 'Shopping', tone: 'done' as const, confirmedLine: 'Pattern confirmed' },
];

const TAG_STYLE: Record<string, { background: string; color: string }> = {
  unknown: { background: 'rgb(148 163 184 / .18)', color: '#CBD5E1' },
  edit: { background: 'rgb(244 241 236 / .16)', color: '#F4F1EC' },
  learn: { background: 'rgb(244 241 236 / .16)', color: '#F4F1EC' },
  done: { background: 'rgb(22 163 74 / .18)', color: '#4ADE80' },
};

export function LearningSection() {
  const prefersReducedMotion = useReducedMotion();

  const containerRef = useRef<HTMLDivElement>(null);
  const card1Ref = useRef<HTMLDivElement>(null);
  const card2Ref = useRef<HTMLDivElement>(null);
  const card3Ref = useRef<HTMLDivElement>(null);
  const card4Ref = useRef<HTMLDivElement>(null);
  const connector1Ref = useRef<HTMLDivElement>(null);
  const connector2Ref = useRef<HTMLDivElement>(null);
  const connector3Ref = useRef<HTMLDivElement>(null);
  const cardRefs = [card1Ref, card2Ref, card3Ref, card4Ref];
  const connectorRefs = [connector1Ref, connector2Ref, connector3Ref];

  useLearningTimeline({
    enabled: !prefersReducedMotion,
    containerRef,
    card1Ref,
    card2Ref,
    card3Ref,
    card4Ref,
    connector1Ref,
    connector2Ref,
    connector3Ref,
  });

  return (
    <Section tone="deep">
      <SectionHeading
        invert
        eyebrow={learning.eyebrow}
        title={<>{learning.title}<br />{learning.titleLine2}</>}
        blurb={learning.blurb}
      />
      {/* grid at base/sm (2-column wrap, matches today), flex row at lg where there's room for
          connectors between cards -- `display: contents` on the per-stage wrapper at base makes
          each wrapper invisible to the grid (its card/connector children participate directly),
          then lg:flex turns the same wrapper into a real flex item holding one card + its
          trailing connector as a unit. */}
      <div ref={containerRef} className="grid grid-cols-1 sm:grid-cols-2 gap-3 lg:flex lg:gap-0 lg:items-stretch">
        {STAGES.map((s, i) => {
          const isDone = s.tone === 'done';
          const initialCardStyle = prefersReducedMotion
            ? { opacity: 1, transform: 'none' }
            : { opacity: 0, transform: 'translateY(8px)' };
          const initialConnectorStyle = prefersReducedMotion ? { transform: 'scaleX(1)' } : { transform: 'scaleX(0)' };

          return (
            <div key={s.caption} className="contents lg:flex lg:flex-1 lg:items-stretch">
              <div
                ref={cardRefs[i]}
                className="rounded-xl p-4 border relative lg:flex-1"
                style={{
                  background: 'rgb(255 255 255 / .05)',
                  borderColor: isDone ? 'rgb(22 163 74 / .55)' : 'rgb(244 241 236 / .45)',
                  ...initialCardStyle,
                }}
              >
                <p className="text-[10px] uppercase tracking-wide text-slate-400 mb-2">{s.caption}</p>
                <p className="text-sm font-semibold text-slate-100 mb-2">{s.merchant}</p>
                {s.confirmedLine ? (
                  <p
                    data-target="confirmation"
                    className="flex items-center gap-1 text-xs font-medium mb-2"
                    style={{ color: '#4ADE80' }}
                  >
                    <Check size={12} /> {s.confirmedLine}
                  </p>
                ) : null}
                {s.tag ? (
                  <span
                    className="inline-flex items-center gap-1 text-[10px] font-medium px-2 py-1 rounded-md"
                    style={TAG_STYLE[s.tone]}
                  >
                    {isDone ? <Check size={11} /> : null}
                    {s.tag}
                  </span>
                ) : (
                  <span className="inline-block text-[10px] px-2 py-1 rounded-md" style={TAG_STYLE.edit}>
                    learning…
                  </span>
                )}
              </div>
              {i < STAGES.length - 1 ? (
                <div className="hidden lg:flex items-center px-2 flex-none w-8">
                  <div className="w-full h-0.5 rounded-full overflow-hidden" style={{ background: 'rgba(255,255,255,.12)' }}>
                    <div
                      ref={connectorRefs[i]}
                      className="h-full origin-left"
                      style={{ background: '#4ADE80', ...initialConnectorStyle }}
                    />
                  </div>
                </div>
              ) : null}
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
