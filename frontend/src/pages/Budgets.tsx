import { useEffect, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { PiggyBank } from 'lucide-react';
import { budgetsApi, categoriesApi, type CategoryOption } from '../api/endpoints';
import type { Budget } from '../types';
import { FinoraCard, EmptyState, Button, Skeleton } from '../design-system';
import { useDelayedLoading } from '../hooks/useDelayedLoading';

function fmt(n: number) {
  // Negative amounts (e.g. a month where spend exceeded income) must render as "-₹500",
  // not "₹-500" -- string concatenation put the currency symbol before the sign.
  return (n < 0 ? '-₹' : '₹') + Math.round(Math.abs(n)).toLocaleString('en-IN');
}

export default function Budgets() {
  const [budgets, setBudgets] = useState<Budget[]>([]);
  const [newCategory, setNewCategory] = useState('');
  const [newLimit, setNewLimit] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [saving, setSaving] = useState(false);
  // Bug fix: this page had no loading flag at all -- `budgets` started `[]`, which is
  // indistinguishable from "genuinely no budgets set," so the EmptyState rendered immediately on
  // every mount and then popped to real content once the fetch resolved. `loading` now gates the
  // EmptyState branch explicitly; `showSkeleton` (useDelayedLoading) only controls whether a
  // skeleton appears during that gate, not whether the wrong content shows.
  const [loading, setLoading] = useState(true);
  const [categoriesById, setCategoriesById] = useState<Map<string, CategoryOption>>(new Map());
  const queryClient = useQueryClient();

  function load() {
    setLoading(true);
    Promise.all([budgetsApi.list(), categoriesApi.list()])
      .then(([budgetList, categoryList]) => {
        setBudgets(budgetList);
        setCategoriesById(new Map(categoryList.map((c) => [c.id, c])));
      })
      .catch(() => setError('Could not load budgets.'))
      .finally(() => setLoading(false));
  }
  useEffect(load, []);
  const showSkeleton = useDelayedLoading(loading);

  async function addOrUpdate() {
    if (!newCategory || !newLimit) return;
    const limit = parseFloat(newLimit);
    if (!(limit > 0)) {
      setError('Monthly limit must be greater than zero.');
      return;
    }
    setError(null);
    setSaving(true);
    try {
      await budgetsApi.upsert(newCategory, limit);
      setNewCategory('');
      setNewLimit('');
      setSaved(true);
      setTimeout(() => setSaved(false), 2000);
      load();
      // This page keeps its own local `budgets` list (loaded via load() above), but Dashboard
      // reads the same data through TanStack Query under the 'budgets' key (30s staleTime) --
      // without invalidating that cache too, a limit set/changed here wouldn't show up on the
      // Dashboard's Budget Progress widget until the cache aged out on its own. 'dashboard-summary'
      // also gets invalidated since budget overspend feeds its notifications/health score.
      void queryClient.invalidateQueries({ queryKey: ['budgets'] });
      void queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not save this budget. Try again.');
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="space-y-4">
      <FinoraCard padding="sm" className="flex gap-2 items-end">
        <div>
          <label htmlFor="budget-category" className="block text-xs uppercase text-gray-500 mb-1">Category</label>
          <input id="budget-category" value={newCategory} onChange={(e) => setNewCategory(e.target.value)} className="bg-card text-ink border rounded px-2 py-1.5 text-sm" />
        </div>
        <div>
          <label htmlFor="budget-monthly-limit" className="block text-xs uppercase text-gray-500 mb-1">Monthly limit</label>
          <input id="budget-monthly-limit" type="number" value={newLimit} onChange={(e) => setNewLimit(e.target.value)} className="bg-card text-ink border rounded px-2 py-1.5 text-sm" />
        </div>
        <Button onClick={addOrUpdate} loading={saving} size="md" className="uppercase">
          Set Budget
        </Button>
        {saved && <span className="text-success text-xs">Saved.</span>}
      </FinoraCard>
      {error && <p className="text-danger text-sm">{error}</p>}

      <FinoraCard padding="sm" className="space-y-3">
        {loading ? (
          showSkeleton && (
            <Skeleton.Region label="Loading budgets">
              <div className="space-y-3">
                {[0, 1, 2].map((i) => (
                  <div key={i} className="grid grid-cols-[140px_1fr_140px] items-center gap-3">
                    <Skeleton.Text width="w-20" />
                    <Skeleton.Block className="h-2 w-full" />
                    <Skeleton.Text width="w-24" />
                  </div>
                ))}
              </div>
            </Skeleton.Region>
          )
        ) : budgets.length === 0 ? (
          <EmptyState
            icon={PiggyBank}
            iconBg="bg-orange-100"
            iconColor="text-orange-600"
            title="No budgets set"
            desc="Create your first budget above to start tracking spending."
          />
        ) : (
          budgets.map((b) => {
            const pct = b.monthlyLimit > 0 ? Math.min(100, (b.spentThisMonth / b.monthlyLimit) * 100) : 0;
            return (
              <div key={b.id} className="grid grid-cols-[140px_1fr_140px] items-center gap-3 text-sm">
                <span>{b.categoryName}</span>
                <div className="h-2 bg-black/10 rounded overflow-hidden">
                  <div className={`h-full ${pct >= 100 ? 'bg-danger' : pct >= 90 ? 'bg-primary' : 'bg-success'}`} style={{ width: `${pct}%` }} />
                </div>
                <span>{fmt(b.spentThisMonth)} / {fmt(b.monthlyLimit)}</span>
              </div>
            );
          })
        )}
      </FinoraCard>
    </div>
  );
}
