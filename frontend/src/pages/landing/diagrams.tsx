import { ShieldCheck } from 'lucide-react';

/** The shield for the transparency section. Pure SVG -- no asset, scales to any density. */
export function ShieldMark() {
  return (
    <div className="relative grid place-items-center" aria-hidden="true">
      <div
        className="absolute w-56 h-56 rounded-full blur-3xl"
        style={{ background: 'radial-gradient(circle, rgba(244,241,236,.18), transparent 70%)' }}
      />
      <ShieldCheck size={132} strokeWidth={1.1} className="relative text-[#F4F1EC]" />
    </div>
  );
}
