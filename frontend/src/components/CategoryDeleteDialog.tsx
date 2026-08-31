import { useEffect, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { categoriesApi, type CategoryOption } from '../api/endpoints';
import { CategoryCombobox } from './CategoryCombobox';

interface Usage {
  transactionCount: number;
  hasBudget: boolean;
  ruleCount: number;
  /** Learning Engine training rows (merchant_category_learning). A dependent like any other:
   *  its FK cascades, so deleting without a reassignment target destroys it. */
  learningRowCount: number;
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
  // A failed usage fetch used to be indistinguishable from "still loading" -- usage stayed null
  // either way, so Delete sat silently disabled forever with nothing on screen explaining why.
  // Same notice pattern CategoryCombobox uses for its own failed category fetch.
  const [usageFailed, setUsageFailed] = useState(false);

  useEffect(() => {
    setUsageFailed(false);
    categoriesApi.usage(category.id)
      .then((u) => setUsage(u))
      .catch(() => {
        setUsage(null);
        setUsageFailed(true);
      });
  }, [category.id]);

  const hasDependents = usage != null && (usage.transactionCount > 0 || usage.hasBudget
    || usage.ruleCount > 0 || usage.learningRowCount > 0);
  const canDelete = usage != null && (!hasDependents || targetId != null);

  const confirm = async () => {
    setDeleting(true);
    setError(null);
    try {
      await categoriesApi.delete(category.id, targetId);
      void queryClient.invalidateQueries({ queryKey: ['categories'] });
      // Reassignment rewrites transactions' categories, so anything showing them is stale too.
      void queryClient.invalidateQueries({ queryKey: ['transactions'] });
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
          {usage.learningRowCount > 0 && (
            <li>
              {usage.learningRowCount} learned merchant{usage.learningRowCount === 1 ? '' : 's'}
            </li>
          )}
        </ul>
      )}
      {usageFailed && (
        <p className="text-[11px] text-warning">
          Couldn't check what this category is used for — please try again.
        </p>
      )}
      {hasDependents && (
        <div>
          <p className="text-[11px] uppercase text-muted mb-1">Move everything to</p>
          <CategoryCombobox
            value={targetName}
            onChange={setTargetName}
            // Captures the id directly from the selection itself -- an existing row, a fuzzy
            // suggestion, or a category just created via this combobox's own inline "+ Create"
            // flow -- instead of re-resolving it by name against the shared ['categories'] cache,
            // which can still be stale (not yet refetched) at the moment a brand-new category is
            // selected here. See CategoryCombobox's onSelect doc comment.
            onSelect={(c: CategoryOption) => setTargetId(c.id)}
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
