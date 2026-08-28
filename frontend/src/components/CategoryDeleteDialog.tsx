import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { categoriesApi, type CategoryOption } from '../api/endpoints';
import { CategoryCombobox } from './CategoryCombobox';

interface Usage {
  transactionCount: number;
  hasBudget: boolean;
  ruleCount: number;
}

interface CategoryDeleteDialogProps {
  category: CategoryOption;
  onDeleted: () => void;
  onCancel: () => void;
}

export function CategoryDeleteDialog({ category, onDeleted, onCancel }: CategoryDeleteDialogProps) {
  const queryClient = useQueryClient();
  const [usage, setUsage] = useState<Usage | null>(null);
  const [targetName, setTargetName] = useState('');
  const [targetId, setTargetId] = useState<string | undefined>(undefined);
  const [deleting, setDeleting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Same shared ['categories'] cache the target picker below reads, so the name -> id lookup
  // cannot disagree with the list the user actually picked from.
  const allCategories: CategoryOption[] =
    useQuery({ queryKey: ['categories'], queryFn: () => categoriesApi.list(), retry: false }).data ?? [];

  useEffect(() => {
    categoriesApi.usage(category.id).then(setUsage).catch(() => setUsage(null));
  }, [category.id]);

  const hasDependents = usage != null && (usage.transactionCount > 0 || usage.hasBudget || usage.ruleCount > 0);
  const canDelete = usage != null && (!hasDependents || targetId != null);

  const confirm = async () => {
    setDeleting(true);
    setError(null);
    try {
      await categoriesApi.delete(category.id, targetId);
      queryClient.invalidateQueries({ queryKey: ['categories'] });
      // Reassignment rewrites transactions' categories, so anything showing them is stale too.
      queryClient.invalidateQueries({ queryKey: ['transactions'] });
      onDeleted();
    } catch (e: any) {
      setError(e?.response?.data?.message ?? 'Could not delete this category.');
    } finally {
      setDeleting(false);
    }
  };

  return (
    <div className="p-4 border border-border rounded-lg bg-card space-y-3">
      <p className="text-sm">Delete <strong>{category.name}</strong>?</p>
      {usage && (
        <ul className="text-sm text-muted space-y-1">
          <li>{usage.transactionCount} transactions</li>
          {usage.hasBudget && <li>1 budget</li>}
          {usage.ruleCount > 0 && <li>{usage.ruleCount} rule{usage.ruleCount === 1 ? '' : 's'}</li>}
        </ul>
      )}
      {hasDependents && (
        <div>
          <p className="text-[11px] uppercase text-muted mb-1">Move everything to</p>
          <CategoryCombobox
            value={targetName}
            onChange={(name) => {
              setTargetName(name);
              setTargetId(allCategories.find((c) => c.name === name)?.id);
            }}
            excludeCategoryId={category.id}
          />
        </div>
      )}
      {error && <p className="text-[11px] text-danger">{error}</p>}
      <div className="flex gap-2 justify-end">
        <button type="button" className="text-sm px-3 py-1.5" onClick={onCancel}>Cancel</button>
        <button
          type="button"
          className="text-sm px-3 py-1.5 bg-danger text-white rounded-lg disabled:opacity-50"
          disabled={!canDelete || deleting}
          onClick={confirm}
        >
          Delete
        </button>
      </div>
    </div>
  );
}
