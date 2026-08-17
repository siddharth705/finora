import { useEffect, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Target } from 'lucide-react';
import { goalsApi } from '../api/endpoints';
import type { Goal } from '../types';
import { FinoraCard, EmptyState } from '../design-system';

function fmt(n: number) {
  // Negative amounts (e.g. a month where spend exceeded income) must render as "-₹500",
  // not "₹-500" -- string concatenation put the currency symbol before the sign.
  return (n < 0 ? '-₹' : '₹') + Math.round(Math.abs(n)).toLocaleString('en-IN');
}

export default function Goals() {
  const [goals, setGoals] = useState<Goal[]>([]);
  const [name, setName] = useState('');
  const [target, setTarget] = useState('');
  const [current, setCurrent] = useState('');
  const [deadline, setDeadline] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const queryClient = useQueryClient();

  function load() {
    goalsApi.list().then(setGoals).catch(() => setError('Could not load goals.'));
  }
  useEffect(load, []);

  // Dashboard reads goals through TanStack Query under the 'goals' key (30s staleTime, same
  // cache Ledger/Import/AskOnceCard already invalidate after their own mutations). This page
  // keeps its own local `goals` list via load() above, but without also invalidating that shared
  // cache, a goal created/funded/deleted here wouldn't show up on the Dashboard's Goals widget
  // until the cache aged out on its own -- stale numbers on a page whose whole pitch is "watch it
  // update in real time." 'dashboard-summary' goes too since goal progress feeds it.
  function invalidateSharedCaches() {
    void queryClient.invalidateQueries({ queryKey: ['goals'] });
    void queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
  }

  async function addGoal() {
    if (!name || !target) return;
    const targetAmount = parseFloat(target);
    if (!(targetAmount > 0)) {
      setError('Target amount must be greater than zero.');
      return;
    }
    setError(null);
    setSaving(true);
    try {
      await goalsApi.create({ name, targetAmount, currentAmount: parseFloat(current || '0'), targetDate: deadline || undefined });
      setName(''); setTarget(''); setCurrent(''); setDeadline('');
      load();
      invalidateSharedCaches();
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not create this goal. Try again.');
    } finally {
      setSaving(false);
    }
  }

  async function contribute(id: string) {
    const amt = parseFloat(prompt('Contribution amount:') ?? '');
    if (isNaN(amt) || amt <= 0) return;
    try {
      await goalsApi.addContribution(id, amt);
      load();
      invalidateSharedCaches();
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not record this contribution. Try again.');
    }
  }

  async function remove(id: string) {
    if (!confirm('Delete this goal?')) return;
    try {
      await goalsApi.remove(id);
      load();
      invalidateSharedCaches();
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not delete this goal. Try again.');
    }
  }

  return (
    <div className="space-y-4">
      <FinoraCard padding="sm" className="grid grid-cols-2 md:grid-cols-4 gap-2 items-end">
        <div><label htmlFor="goal-name" className="block text-xs uppercase text-gray-500 mb-1">Name</label><input id="goal-name" value={name} onChange={(e) => setName(e.target.value)} className="bg-card text-ink border rounded px-2 py-1.5 text-sm w-full" /></div>
        <div><label htmlFor="goal-target" className="block text-xs uppercase text-gray-500 mb-1">Target</label><input id="goal-target" type="number" value={target} onChange={(e) => setTarget(e.target.value)} className="bg-card text-ink border rounded px-2 py-1.5 text-sm w-full" /></div>
        <div><label htmlFor="goal-starting-amount" className="block text-xs uppercase text-gray-500 mb-1">Starting amount</label><input id="goal-starting-amount" type="number" value={current} onChange={(e) => setCurrent(e.target.value)} className="bg-card text-ink border rounded px-2 py-1.5 text-sm w-full" /></div>
        <div><label htmlFor="goal-target-date" className="block text-xs uppercase text-gray-500 mb-1">Target date</label><input id="goal-target-date" type="date" value={deadline} onChange={(e) => setDeadline(e.target.value)} className="bg-card text-ink border rounded px-2 py-1.5 text-sm w-full" /></div>
        <button onClick={addGoal} disabled={saving} className="bg-primary text-white hover:bg-primary-dark px-4 py-2 rounded text-xs uppercase col-span-2 md:col-span-1 disabled:opacity-50">
          {saving ? 'Adding…' : 'Add Goal'}
        </button>
      </FinoraCard>
      {error && <p className="text-danger text-sm">{error}</p>}

      <div className="space-y-3">
        {goals.length === 0 ? (
          <FinoraCard padding="sm">
            <EmptyState
              icon={Target}
              iconBg="bg-red-100"
              iconColor="text-red-600"
              title="No goals yet"
              desc="Add your first goal above to start tracking progress."
            />
          </FinoraCard>
        ) : (
          goals.map((g) => {
            const pct = g.targetAmount > 0 ? Math.min(100, (g.currentAmount / g.targetAmount) * 100) : 0;
            return (
              <FinoraCard key={g.id} padding="sm">
                <div className="flex justify-between items-baseline mb-2">
                  <span className="font-serif text-lg font-semibold">{g.name}</span>
                  <span className="text-sm text-gray-500">{fmt(g.currentAmount)} / {fmt(g.targetAmount)}</span>
                </div>
                <div className="h-2 bg-black/10 rounded overflow-hidden mb-2">
                  <div className="h-full bg-success" style={{ width: `${pct}%` }} />
                </div>
                <div className="flex justify-between text-xs text-gray-500">
                  <span>{pct.toFixed(0)}% complete{g.targetDate ? ` · target ${g.targetDate}` : ''}</span>
                  <span className="flex gap-2">
                    <button onClick={() => contribute(g.id)} className="border rounded px-2 py-1 uppercase">Add Contribution</button>
                    <button onClick={() => remove(g.id)} className="border border-danger text-danger rounded px-2 py-1 uppercase">Delete</button>
                  </span>
                </div>
              </FinoraCard>
            );
          })
        )}
      </div>
    </div>
  );
}
