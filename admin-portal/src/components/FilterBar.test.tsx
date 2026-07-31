import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { FilterBar, type FilterField } from './FilterBar';

interface Filters extends Record<string, string> {
  q: string;
  status: string;
}

function baseFields(onSearchChange = vi.fn(), onStatusChange = vi.fn()): FilterField[] {
  return [
    { type: 'search', key: 'q', value: '', onChange: onSearchChange, placeholder: 'Search…' },
    {
      type: 'select', key: 'status', value: '', onChange: onStatusChange, placeholder: 'All statuses',
      options: [{ label: 'Active', value: 'ACTIVE' }, { label: 'Suspended', value: 'SUSPENDED' }],
    },
  ];
}

describe('FilterBar', () => {
  it('calls the search field onChange as the user types', async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<FilterBar<Filters> fields={baseFields(onChange)} />);

    await user.type(screen.getByPlaceholderText('Search…'), 'a');

    expect(onChange).toHaveBeenCalledWith('a');
  });

  it('calls onApply when Enter is pressed in a search field', async () => {
    const onApply = vi.fn();
    const user = userEvent.setup();
    render(<FilterBar<Filters> fields={baseFields()} onApply={onApply} />);

    await user.type(screen.getByPlaceholderText('Search…'), '{Enter}');

    expect(onApply).toHaveBeenCalled();
  });

  it('calls onApply when the apply button is clicked', async () => {
    const onApply = vi.fn();
    const user = userEvent.setup();
    render(<FilterBar<Filters> fields={baseFields()} onApply={onApply} applyLabel="Search" />);

    await user.click(screen.getByRole('button', { name: 'Search' }));

    expect(onApply).toHaveBeenCalled();
  });

  it('calls a select field onChange immediately on selection, without needing Apply', async () => {
    const onStatusChange = vi.fn();
    const user = userEvent.setup();
    render(<FilterBar<Filters> fields={baseFields(vi.fn(), onStatusChange)} />);

    await user.selectOptions(screen.getByDisplayValue('All statuses'), 'ACTIVE');

    expect(onStatusChange).toHaveBeenCalledWith('ACTIVE');
  });

  it('renders trailingActions', () => {
    render(
      <FilterBar<Filters> fields={baseFields()} trailingActions={<button type="button">New user</button>} />
    );

    expect(screen.getByRole('button', { name: 'New user' })).toBeInTheDocument();
  });

  it('renders date fields and calls onChange on input', () => {
    const onChange = vi.fn();
    render(
      <FilterBar<Filters>
        fields={[{ type: 'date', key: 'dateFrom', label: 'From', value: '', onChange }]}
      />
    );

    const input = screen.getByLabelText('From') as HTMLInputElement;
    fireEvent.change(input, { target: { value: '2026-01-15' } });

    expect(onChange).toHaveBeenCalledWith('2026-01-15');
  });

  describe('saved views', () => {
    function renderWithSavedViews(overrides: Partial<{
      views: { name: string; values: Filters }[];
      onApply: (v: Filters) => void;
      onSave: (name: string, v: Filters) => void;
      onDelete: (name: string) => void;
    }> = {}) {
      const onApply = overrides.onApply ?? vi.fn();
      const onSave = overrides.onSave ?? vi.fn();
      const onDelete = overrides.onDelete ?? vi.fn();
      render(
        <FilterBar<Filters>
          fields={baseFields()}
          savedViews={{
            views: overrides.views ?? [],
            currentValues: { q: 'amazon', status: 'ACTIVE' },
            onApply,
            onSave,
            onDelete,
          }}
        />
      );
      return { onApply, onSave, onDelete };
    }

    it('shows "no saved views" when the list is empty', async () => {
      const user = userEvent.setup();
      renderWithSavedViews();

      await user.click(screen.getByRole('button', { name: /Views/ }));

      expect(screen.getByText('No saved views yet.')).toBeInTheDocument();
    });

    it('lists existing saved views and applies one on click', async () => {
      const user = userEvent.setup();
      const { onApply } = renderWithSavedViews({
        views: [{ name: 'My View', values: { q: 'amazon', status: 'ACTIVE' } }],
      });

      await user.click(screen.getByRole('button', { name: /Views/ }));
      await user.click(screen.getByText('My View'));

      expect(onApply).toHaveBeenCalledWith({ q: 'amazon', status: 'ACTIVE' });
    });

    it('deletes a saved view via its delete button', async () => {
      const user = userEvent.setup();
      const { onDelete } = renderWithSavedViews({
        views: [{ name: 'My View', values: { q: 'amazon', status: 'ACTIVE' } }],
      });

      await user.click(screen.getByRole('button', { name: /Views/ }));
      await user.click(screen.getByLabelText('Delete view My View'));

      expect(onDelete).toHaveBeenCalledWith('My View');
    });

    it('saves the current filter values under a new name', async () => {
      const user = userEvent.setup();
      const { onSave } = renderWithSavedViews();

      await user.click(screen.getByRole('button', { name: /Views/ }));
      await user.click(screen.getByText('+ Save current filters'));
      await user.type(screen.getByPlaceholderText('View name'), 'Suspended users');
      await user.click(screen.getByRole('button', { name: 'Save' }));

      expect(onSave).toHaveBeenCalledWith('Suspended users', { q: 'amazon', status: 'ACTIVE' });
    });
  });
});
