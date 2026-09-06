import { useState } from 'react';
import { Button } from '../design-system';

const OPTIONS: { key: string; label: string }[] = [
  { key: 'TRACK_SPENDING', label: '💰 Track my spending' },
  { key: 'MANAGE_BUDGETS', label: '📊 Create and manage budgets' },
  { key: 'SAVE_FOR_GOAL', label: '🎯 Save for a goal' },
  { key: 'SEE_ALL_ACCOUNTS', label: '🏦 See all my accounts in one place' },
  { key: 'IMPROVE_HABITS', label: '📈 Improve my financial habits' },
  { key: 'REDUCE_DEBT', label: '💳 Reduce debt' },
  { key: 'EXPLORING', label: '🔍 Just exploring' },
];

interface Props {
  onContinue: (selected: string[]) => void;
}

export function FinancialFocusScreen({ onContinue }: Props) {
  const [selected, setSelected] = useState<string[]>([]);

  function toggle(key: string) {
    if (key === 'EXPLORING') {
      setSelected((prev) => (prev.includes('EXPLORING') ? [] : ['EXPLORING']));
      return;
    }
    setSelected((prev) => {
      const withoutExploring = prev.filter((k) => k !== 'EXPLORING');
      return withoutExploring.includes(key)
        ? withoutExploring.filter((k) => k !== key)
        : [...withoutExploring, key];
    });
  }

  return (
    <div className="flex flex-col items-center justify-center min-h-screen px-6 text-center">
      <h1 className="text-2xl font-bold text-ink mb-2">What would you like to achieve with Fynora?</h1>
      <p className="text-muted mb-6">Select all that apply. We'll personalize your experience.</p>
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 max-w-lg mb-8">
        {OPTIONS.map((opt) => (
          <button
            key={opt.key}
            type="button"
            onClick={() => toggle(opt.key)}
            className={`px-4 py-3 rounded-lg border text-sm text-left transition-colors ${
              selected.includes(opt.key) ? 'border-primary bg-primary/10 text-ink' : 'border-border text-muted'
            }`}
          >
            {opt.label}
          </button>
        ))}
      </div>
      <Button variant="primary" onClick={() => onContinue(selected)}>Continue</Button>
    </div>
  );
}
