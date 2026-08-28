import { useEffect, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { categoriesApi, type CategoryOption, type CategoryOptions } from '../api/endpoints';

interface CategoryCreateEditPanelProps {
  mode: 'create' | 'edit';
  initialName?: string;
  categoryId?: string;
  initialIcon?: string;
  initialColor?: string;
  onSaved: (category: CategoryOption) => void;
  onCancel: () => void;
}

export function CategoryCreateEditPanel({
  mode, initialName = '', categoryId, initialIcon = 'tag', initialColor = 'gray', onSaved, onCancel,
}: CategoryCreateEditPanelProps) {
  const queryClient = useQueryClient();
  const [options, setOptions] = useState<CategoryOptions>({ icons: [], colors: [] });
  const [name, setName] = useState(initialName);
  const [icon, setIcon] = useState(initialIcon);
  const [color, setColor] = useState(initialColor);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    categoriesApi.options().then(setOptions).catch(() => setOptions({ icons: [], colors: [] }));
  }, []);

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
      queryClient.invalidateQueries({ queryKey: ['categories'] });
      onSaved(saved);
    } catch (e: any) {
      setError(e?.response?.data?.message ?? 'Could not save this category.');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="p-3 border border-border rounded-lg bg-card space-y-3">
      <input
        className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full"
        value={name}
        maxLength={80}
        onChange={(e) => setName(e.target.value)}
        placeholder="Category name"
      />
      <div className="flex flex-wrap gap-2">
        {options.icons.map((i) => (
          <button
            key={i.token}
            type="button"
            aria-label={i.label}
            className={`px-2 py-1 rounded border text-xs ${icon === i.token ? 'border-primary' : 'border-border'}`}
            onClick={() => setIcon(i.token)}
          >
            {i.label}
          </button>
        ))}
      </div>
      <div className="flex flex-wrap gap-2">
        {options.colors.map((c) => (
          <button
            key={c.token}
            type="button"
            aria-label={c.token}
            className={`w-6 h-6 rounded-full border-2 ${color === c.token ? 'border-primary' : 'border-transparent'}`}
            style={{ backgroundColor: c.label }}
            onClick={() => setColor(c.token)}
          />
        ))}
      </div>
      {error && <p className="text-[11px] text-danger">{error}</p>}
      <div className="flex gap-2 justify-end">
        <button type="button" className="text-sm px-3 py-1.5" onClick={onCancel}>Cancel</button>
        <button
          type="button"
          className="text-sm px-3 py-1.5 bg-primary text-on-primary rounded-lg"
          disabled={saving}
          onClick={save}
        >
          Save
        </button>
      </div>
    </div>
  );
}
