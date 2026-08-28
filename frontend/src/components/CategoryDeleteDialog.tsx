import { useEffect, useState } from 'react';
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
  const [usage, setUsage] = useState<Usage | null>(null);
  const [targetName, setTargetName] = useState('');
  const [targetId, setTargetId] = useState<string | undefined>(undefined);
  const [allCategories, setAllCategories] = useState<CategoryOption[]>([]);
  const [deleting, setDeleting] = useState(false);

  useEffect(() => {
    categoriesApi.usage(category.id).then(setUsage).catch(() => setUsage(null));
    categoriesApi.list().then(setAllCategories).catch(() => setAllCategories([]));
  }, [category.id]);

  const hasDependents = usage != null && (usage.transactionCount > 0 || usage.hasBudget || usage.ruleCount > 0);
  const canDelete = usage != null && (!hasDependents || targetId != null);

  const confirm = async () => {
    setDeleting(true);
    try {
      await categoriesApi.delete(category.id, targetId);
      onDeleted();
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
