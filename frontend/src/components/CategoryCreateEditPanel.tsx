import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { motion } from 'framer-motion';
import { Check, Loader2, RotateCw, Tag } from 'lucide-react';
import { categoriesApi, type CategoryOption } from '../api/endpoints';
import { ICON_COMPONENTS } from '../lib/categoryIcons';

interface CategoryCreateEditPanelProps {
  mode: 'create' | 'edit';
  initialName?: string;
  categoryId?: string;
  initialIcon?: string;
  initialColor?: string;
  onSaved: (category: CategoryOption) => void;
  onCancel: () => void;
}

function IconGridSkeleton() {
  return (
    <div className="grid grid-cols-6 gap-1.5" aria-hidden="true">
      {Array.from({ length: 12 }).map((_, i) => (
        <div key={i} className="aspect-square rounded-lg bg-surface animate-pulse" />
      ))}
    </div>
  );
}

function ColorRowSkeleton() {
  return (
    <div className="flex flex-wrap gap-2" aria-hidden="true">
      {Array.from({ length: 8 }).map((_, i) => (
        <div key={i} className="w-6 h-6 rounded-full bg-surface animate-pulse" />
      ))}
    </div>
  );
}

export function CategoryCreateEditPanel({
  mode, initialName = '', categoryId, initialIcon = 'tag', initialColor = 'gray', onSaved, onCancel,
}: CategoryCreateEditPanelProps) {
  const queryClient = useQueryClient();
  // Static reference data (the curated icon/color allow-list) -- staleTime: Infinity means this
  // only ever fetches once per session, cached under the same key CategoryCombobox prefetches on
  // popover-open, so this panel almost never shows the loading state below in practice. Without
  // this, every "+ New category" click re-fetched from a blank { icons: [], colors: [] } default,
  // which is where the ~1s blank-grid delay on every single open came from.
  const optionsQ = useQuery({
    queryKey: ['category-options'],
    queryFn: categoriesApi.options,
    staleTime: Infinity,
  });
  const options = optionsQ.data ?? { icons: [], colors: [] };
  const [name, setName] = useState(initialName);
  const [icon, setIcon] = useState(initialIcon);
  const [color, setColor] = useState(initialColor);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const save = async () => {
    if (!name.trim()) {
      setError('Enter a name for this category.');
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const saved = mode === 'create'
        ? await categoriesApi.create(name.trim(), icon, color)
        : await categoriesApi.update(categoryId!, { name: name.trim(), icon, color });
      // Every CategoryCombobox on the page reads the same ['categories'] query. Without this, a
      // category created in one of them stays invisible to its siblings, which then offer
      // "+ Create" for a name that now exists and 409 on the second create.
      void queryClient.invalidateQueries({ queryKey: ['categories'] });
      // A rename changes the category's display name everywhere it is shown, transaction rows
      // included -- same reason CategoryDeleteDialog invalidates this after a reassignment.
      // Create can't affect existing transactions, so it is left alone.
      if (mode === 'edit') void queryClient.invalidateQueries({ queryKey: ['transactions'] });
      onSaved(saved);
    } catch (e: any) {
      setError(e?.response?.data?.message ?? 'Could not save this category.');
    } finally {
      setSaving(false);
    }
  };

  return (
    <motion.div
      initial={{ opacity: 0, y: 4, scale: 0.98 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      transition={{ duration: 0.15, ease: 'easeOut' }}
      className="p-3.5 border border-border rounded-lg bg-card shadow-sm space-y-3.5"
    >
      <input
        className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full focus:outline-none focus:ring-2 focus:ring-primary/40 focus:border-primary transition-shadow"
        value={name}
        maxLength={80}
        onChange={(e) => setName(e.target.value)}
        placeholder="Category name"
        autoFocus
      />

      {optionsQ.isError && (
        <div className="flex items-center justify-between gap-2 bg-warning-bg text-warning text-[11px] rounded-lg px-2.5 py-2">
          <span>Couldn't load icons and colors.</span>
          <button
            type="button"
            onClick={() => void optionsQ.refetch()}
            className="flex items-center gap-1 font-medium hover:underline flex-shrink-0"
          >
            <RotateCw size={11} /> Retry
          </button>
        </div>
      )}

      <div className="space-y-1.5">
        <p className="text-[11px] uppercase text-muted tracking-wide font-medium">Icon</p>
        {optionsQ.isLoading ? (
          <IconGridSkeleton />
        ) : (
          <div className="grid grid-cols-6 gap-1.5">
            {options.icons.map((i) => {
              const Icon = ICON_COMPONENTS[i.token] ?? Tag;
              const isSelected = icon === i.token;
              return (
                <button
                  key={i.token}
                  type="button"
                  aria-label={i.label}
                  aria-pressed={isSelected}
                  title={i.label}
                  className={`flex items-center justify-center p-1.5 rounded-lg border transition-all ${
                    isSelected
                      ? 'border-primary bg-primary-light text-primary'
                      : 'border-border text-muted hover:text-ink hover:bg-surface hover:border-ink/20'
                  }`}
                  onClick={() => setIcon(i.token)}
                >
                  <Icon size={15} />
                </button>
              );
            })}
          </div>
        )}
      </div>

      <div className="space-y-1.5">
        <p className="text-[11px] uppercase text-muted tracking-wide font-medium">Color</p>
        {optionsQ.isLoading ? (
          <ColorRowSkeleton />
        ) : (
          <div className="flex flex-wrap gap-2">
            {options.colors.map((c) => {
              const isSelected = color === c.token;
              return (
                <button
                  key={c.token}
                  type="button"
                  aria-label={c.token}
                  aria-pressed={isSelected}
                  className={`relative w-6 h-6 rounded-full flex items-center justify-center transition-transform hover:scale-110 ${
                    isSelected ? 'ring-2 ring-offset-2 ring-offset-card ring-primary' : ''
                  }`}
                  style={{ backgroundColor: c.label }}
                  onClick={() => setColor(c.token)}
                >
                  {isSelected && <Check size={12} className="text-white drop-shadow" />}
                </button>
              );
            })}
          </div>
        )}
      </div>

      {error && <p className="text-[11px] text-danger">{error}</p>}

      <div className="flex gap-2 justify-end pt-0.5">
        <motion.button
          type="button"
          whileTap={{ scale: 0.96 }}
          className="text-sm px-3 py-1.5 rounded-lg text-muted hover:text-ink hover:bg-surface transition-colors"
          onClick={onCancel}
        >
          Cancel
        </motion.button>
        <motion.button
          type="button"
          whileTap={{ scale: 0.96 }}
          className="text-sm px-3 py-1.5 bg-primary text-on-primary rounded-lg flex items-center gap-1.5 disabled:opacity-60"
          disabled={saving}
          onClick={save}
        >
          {saving && <Loader2 size={13} className="animate-spin" />}
          Save
        </motion.button>
      </div>
    </motion.div>
  );
}
