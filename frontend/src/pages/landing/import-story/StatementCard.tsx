const ROWS = [
  { label: 'Amazon Pay', amount: '-2,450.00' },
  { label: 'Swiggy Bangalore', amount: '-860.00' },
  { label: 'Salary credit', amount: '+1,24,500.00' },
  { label: 'Netflix subscription', amount: '-649.00' },
];

interface StatementCardProps {
  /** Whether the scan-line sweep is currently playing -- the "processing" beat. */
  scanning?: boolean;
}

/**
 * Beat 1 (at rest) and beat 2 (scanning) of the ImportSection reveal sequence, in one component:
 * a realistic statement -- real-looking row labels and amounts, not generic PDF/CSV icons --
 * scanned by a sweeping highlight band. Deliberately bank-agnostic ("Bank statement", no real
 * institution named): this is Finora's own illustration, not a claim of partnership with any
 * specific bank. The merchant names (Amazon, Swiggy, Netflix) are the same example transactions
 * already used elsewhere on this page (LearningSection, IntelligencePanel) -- naming a real
 * merchant a statement line item is descriptive, not an endorsement claim the way naming the
 * issuing bank would be.
 */
export function StatementCard({ scanning = false }: StatementCardProps) {
  return (
    <div
      className="relative w-[220px] rounded-lg bg-white border border-[#E6EAF2] p-4 overflow-hidden"
      style={{ boxShadow: '0 24px 48px -20px rgba(15,23,42,.3)', transform: 'rotate(-2deg)' }}
    >
      <p className="text-[9px] uppercase tracking-wide text-slate-400">Bank statement</p>
      <p className="text-xs font-semibold text-slate-900 mb-2.5">Aug 2026</p>
      <div className="flex flex-col gap-1.5">
        {ROWS.map((row) => (
          <div key={row.label} className="flex items-center justify-between border-b border-slate-100 pb-1.5 last:border-b-0 last:pb-0">
            <span className="text-[9px] text-slate-500">{row.label}</span>
            <span className="text-[9px] font-semibold text-slate-800">{row.amount}</span>
          </div>
        ))}
      </div>
      {scanning ? (
        <div
          aria-hidden="true"
          className="absolute left-0 right-0 h-6 pointer-events-none"
          style={{
            background: 'linear-gradient(180deg, rgba(22,163,74,0) 0%, rgba(22,163,74,.16) 50%, rgba(22,163,74,0) 100%)',
            borderTop: '1px solid rgba(22,163,74,.55)',
            animation: 'statement-scan 700ms ease-in-out forwards',
          }}
        />
      ) : null}
    </div>
  );
}
