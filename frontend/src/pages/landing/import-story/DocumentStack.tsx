import { forwardRef } from 'react';
import { FileText, Sheet } from 'lucide-react';

/**
 * Beat 1 of the ImportSection reveal sequence: statements scattered, not yet processed. Purely
 * presentational -- swapped in and out whole by ImportRevealSequence, not internally animated.
 */
export const DocumentStack = forwardRef<HTMLDivElement>(function DocumentStack(_props, ref) {
  return (
    <div ref={ref} className="relative w-full h-full">
      <div
        data-target="doc-pdf"
        className="absolute left-[18%] top-[20%] w-20 h-28 rounded-lg bg-white border border-[#E6EAF2] shadow-lg flex flex-col items-center justify-center gap-1"
        style={{ transform: 'rotate(-8deg)' }}
      >
        <FileText size={20} color="#EF4444" />
        <span className="text-[10px] font-semibold text-slate-500">PDF</span>
      </div>
      <div
        data-target="doc-csv"
        className="absolute right-[20%] top-[32%] w-20 h-28 rounded-lg bg-white border border-[#E6EAF2] shadow-lg flex flex-col items-center justify-center gap-1"
        style={{ transform: 'rotate(6deg)' }}
      >
        <Sheet size={20} color="#16A34A" />
        <span className="text-[10px] font-semibold text-slate-500">CSV</span>
      </div>
      {[
        { amt: '₹2,450', top: '58%', left: '30%', tint: '#16A34A' },
        { amt: '₹1,280', top: '68%', left: '55%', tint: '#2563EB' },
        { amt: '₹860', top: '48%', left: '68%', tint: '#F59E0B' },
      ].map((chip, i) => (
        <div
          key={chip.amt}
          data-target={`chip-${i}`}
          className="absolute rounded-full bg-white border border-[#E6EAF2] shadow-md px-3 py-1.5 text-[11px] font-medium text-slate-600"
          style={{ top: chip.top, left: chip.left }}
        >
          <span className="inline-block w-1.5 h-1.5 rounded-full mr-1.5" style={{ background: chip.tint }} />
          {chip.amt}
        </div>
      ))}
    </div>
  );
});
