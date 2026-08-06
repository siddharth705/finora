import { useEffect, useRef, useState } from 'react';
import { FileText, Lock, Sheet, ShieldCheck } from 'lucide-react';
import { FlowArrow, Reveal } from './primitives';

/**
 * The three explanatory diagrams. Each one shows a mechanism the product actually implements --
 * that is the whole reason they earn their space over another paragraph.
 *
 * All are decorative in the accessibility sense: every one sits next to prose saying the same
 * thing, so they are hidden from assistive tech rather than narrated twice.
 */

/** PDF / CSV / protected PDF -> Finora -> dashboard. */
export function ImportFlow() {
  const inputs = [
    { icon: <FileText size={15} />, label: 'PDF', tint: '#EF4444' },
    { icon: <Sheet size={15} />, label: 'CSV', tint: '#16A34A' },
    { icon: <Lock size={15} />, label: 'Protected PDF', tint: '#2563EB' },
  ];
  return (
    <Reveal className="m-card p-6 sm:p-8">
      <div className="flex flex-col sm:flex-row items-center justify-center gap-4 sm:gap-5">
        <div className="flex sm:flex-col gap-2 flex-wrap justify-center">
          {inputs.map((i) => (
            <span key={i.label} className="m-node">
              <span style={{ color: i.tint }}>{i.icon}</span>
              {i.label}
            </span>
          ))}
        </div>

        <FlowArrow />

        <div className="flex flex-col items-center gap-1.5">
          <div className="w-14 h-14 rounded-2xl bg-[#2563EB] text-white grid place-items-center font-extrabold text-xl shadow-[0_12px_28px_-12px_rgba(37,99,235,.9)]">
            F
          </div>
          <span className="text-[11px] text-slate-400">reads &amp; organizes</span>
        </div>

        <FlowArrow />

        <div className="w-full sm:w-52 rounded-xl border border-[#E6EAF2] p-3">
          <p className="text-[10px] uppercase tracking-wide text-slate-400 mb-2">Transactions</p>
          {[['₹2,450', '#16A34A'], ['₹1,280', '#2563EB'], ['₹860', '#F59E0B'], ['₹3,650', '#7C3AED']].map(([amt, tint]) => (
            <div key={amt} className="flex items-center gap-2 mb-1.5 last:mb-0">
              <span className="w-1.5 h-1.5 rounded-full" style={{ background: tint }} />
              <span className="h-1.5 rounded-full bg-[#EEF2F7] flex-1" />
              <span className="text-[10px] text-slate-500">{amt}</span>
            </div>
          ))}
        </div>
      </div>
    </Reveal>
  );
}

/**
 * The learning loop. Steps in only once the diagram is on screen, so the correction is something
 * the visitor watches happen rather than a static before/after they have to decode.
 *
 * This mirrors a real capability -- a corrected merchant is remembered and applied to later
 * imports -- not an aspiration.
 */
export function LearningFlow() {
  const ref = useRef<HTMLDivElement | null>(null);
  const [step, setStep] = useState(0);

  useEffect(() => {
    const node = ref.current;
    if (!node || typeof IntersectionObserver === 'undefined') {
      setStep(4); // no observer (tests, old browsers): show the finished state, never a blank one
      return;
    }
    if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) {
      setStep(4);
      return;
    }
    let timers: ReturnType<typeof setTimeout>[] = [];
    const observer = new IntersectionObserver(([entry]) => {
      if (!entry.isIntersecting) return;
      observer.disconnect();
      timers = [1, 2, 3, 4].map((s) => setTimeout(() => setStep(s), s * 620));
    }, { threshold: 0.35 });
    observer.observe(node);
    return () => {
      observer.disconnect();
      timers.forEach(clearTimeout);
    };
  }, []);

  const stages = [
    { caption: 'Imported', value: 'Amazon', tag: 'Shopping', tone: 'muted' },
    { caption: 'You change it to', value: 'Amazon', tag: 'Household', tone: 'edit' },
    { caption: 'Finora learns', value: 'Updating pattern…', tag: null, tone: 'learn' },
    { caption: 'Next import', value: 'Amazon', tag: 'Household', tone: 'done' },
  ];

  return (
    <div ref={ref} className="grid sm:grid-cols-2 lg:grid-cols-4 gap-3">
      {stages.map((s, i) => {
        const active = step >= i + 1;
        return (
          <div
            key={s.caption}
            className="rounded-xl p-4 border transition-all duration-500"
            style={{
              background: 'rgb(255 255 255 / .05)',
              borderColor: active ? 'rgb(37 99 235 / .55)' : 'rgb(255 255 255 / .10)',
              opacity: active ? 1 : 0.45,
              transform: active ? 'none' : 'translateY(6px)',
            }}
          >
            <p className="text-[10px] uppercase tracking-wide text-slate-400 mb-2">{s.caption}</p>
            <p className="text-sm font-semibold text-slate-100 mb-2">{s.value}</p>
            {s.tag ? (
              <span
                className="inline-block text-[10px] font-medium px-2 py-1 rounded-md"
                style={
                  s.tone === 'muted'
                    ? { background: 'rgb(148 163 184 / .18)', color: '#CBD5E1' }
                    : { background: 'rgb(22 163 74 / .18)', color: '#4ADE80' }
                }
              >
                {s.tag}
              </span>
            ) : (
              <span className="inline-block text-[10px] px-2 py-1 rounded-md" style={{ background: 'rgb(37 99 235 / .22)', color: '#93C5FD' }}>
                learning…
              </span>
            )}
          </div>
        );
      })}
    </div>
  );
}

/** Months accumulating into a timeline, then widening into what the timeline makes possible. */
export function StoryTimeline() {
  const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May'];
  const outcomes = ['Insights', 'Habits', 'Spending patterns', 'Financial trends'];
  return (
    <Reveal className="m-card p-6 sm:p-8">
      <div className="flex flex-wrap items-stretch justify-center gap-2.5 mb-6">
        {months.map((m, i) => (
          <div key={m} className="rounded-xl border border-[#E6EAF2] px-3.5 py-3 min-w-[92px]">
            <p className="text-[11px] font-semibold text-slate-900 mb-2">{m}</p>
            {[['Income', '#16A34A'], ['Expenses', '#EF4444'], ['Savings', '#2563EB']].map(([label, tint]) => (
              <div key={label} className="flex items-center gap-1.5 mb-1 last:mb-0">
                <span className="w-1.5 h-1.5 rounded-full" style={{ background: tint }} />
                {/* Bars grow month over month -- the accumulation IS the point of the section. */}
                <span
                  className="h-1 rounded-full"
                  style={{ background: '#EEF2F7', width: `${26 + i * 8}px` }}
                />
              </div>
            ))}
          </div>
        ))}
      </div>

      <div className="flex justify-center mb-5"><FlowArrow vertical /></div>

      <div className="flex flex-wrap justify-center gap-2.5">
        {outcomes.map((o) => (
          <span key={o} className="m-node" style={{ borderColor: '#DBEAFE', background: '#EFF5FF', color: '#1D4ED8' }}>
            {o}
          </span>
        ))}
      </div>
    </Reveal>
  );
}

/** The shield for the transparency section. Pure SVG -- no asset, scales to any density. */
export function ShieldMark() {
  return (
    <div className="relative grid place-items-center" aria-hidden="true">
      <div
        className="absolute w-56 h-56 rounded-full blur-3xl"
        style={{ background: 'radial-gradient(circle, rgba(37,99,235,.38), transparent 70%)' }}
      />
      <ShieldCheck size={132} strokeWidth={1.1} className="relative text-[#60A5FA]" />
    </div>
  );
}
