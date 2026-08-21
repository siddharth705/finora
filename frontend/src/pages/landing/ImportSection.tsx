import { FileText, Lock, Sheet } from 'lucide-react';
import { Eyebrow, FlowArrow, Reveal, Section } from './primitives';
import { importSection } from './landing-config';

/**
 * Import, shown as a mechanism rather than described as one.
 *
 * The whole card is a hover group: the input chips lift and tint, the arrows slide, the mark
 * lights up, and the output rows tick in. It is one gesture rather than four separate hovers,
 * because the point being made is that these stages are a single motion the user doesn't manage.
 *
 * Every format listed is genuinely supported today -- password-protected PDFs and multi-account
 * composite statements included. Nothing aspirational in this list.
 */
const INPUTS = [
  { icon: <FileText size={15} />, label: 'PDF', tint: '#EF4444' },
  { icon: <Sheet size={15} />, label: 'CSV', tint: '#16A34A' },
  { icon: <Lock size={15} />, label: 'Protected PDF', tint: '#2563EB' },
];

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
          <div className="m-card group p-6 sm:p-8 cursor-default">
            <div className="flex flex-col sm:flex-row items-center justify-center gap-4 sm:gap-5">
              <div className="flex sm:flex-col gap-2 flex-wrap justify-center">
                {INPUTS.map((i, idx) => (
                  <span
                    key={i.label}
                    className="m-node transition-all duration-300 group-hover:-translate-y-0.5 group-hover:shadow-md"
                    style={{ transitionDelay: `${idx * 60}ms` }}
                  >
                    <span style={{ color: i.tint }}>{i.icon}</span>
                    {i.label}
                  </span>
                ))}
              </div>

              <span className="transition-transform duration-300 group-hover:translate-x-1"><FlowArrow /></span>

              <div className="flex flex-col items-center gap-1.5">
                <div
                  className="w-14 h-14 rounded-2xl bg-[var(--m-brand)] text-white grid place-items-center font-extrabold text-xl transition-all duration-300 group-hover:scale-105"
                  style={{ boxShadow: '0 12px 28px -12px rgba(38,42,51,.9)' }}
                >
                  F
                </div>
                <span className="text-[11px] text-slate-400">reads &amp; organizes</span>
              </div>

              <span className="transition-transform duration-300 group-hover:translate-x-1"><FlowArrow /></span>

              <div className="w-full sm:w-52 rounded-xl border border-[#E6EAF2] p-3">
                <p className="text-[10px] uppercase tracking-wide text-slate-400 mb-2">Transactions</p>
                {[['₹2,450', '#16A34A'], ['₹1,280', '#2563EB'], ['₹860', '#F59E0B'], ['₹3,650', '#7C3AED']].map(([amt, tint], idx) => (
                  <div
                    key={amt}
                    className="flex items-center gap-2 mb-1.5 last:mb-0 transition-all duration-300 group-hover:translate-x-0.5"
                    style={{ transitionDelay: `${120 + idx * 70}ms` }}
                  >
                    <span className="w-1.5 h-1.5 rounded-full" style={{ background: tint }} />
                    <span className="h-1.5 rounded-full bg-[#EEF2F7] flex-1" />
                    <span className="text-[10px] text-slate-500">{amt}</span>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </Reveal>
      </div>
    </Section>
  );
}
