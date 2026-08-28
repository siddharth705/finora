import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { CategoryCombobox } from './CategoryCombobox';
import { categoriesApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  categoriesApi: {
    list: vi.fn(),
    options: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
    usage: vi.fn(),
  },
}));

const CATEGORIES = [
  { id: '1', name: 'SIP', isSystem: false, icon: 'tag', color: 'gray' },
  { id: '2', name: 'Investments', isSystem: true, icon: 'trending-up', color: 'teal' },
  { id: '3', name: 'Groceries', isSystem: true, icon: 'shopping-cart', color: 'green' },
];

// A fresh client per render, so one test's ['categories'] cache never leaks into the next.
function renderWithClient(ui: ReactElement) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>);
}

describe('CategoryCombobox', () => {
  beforeEach(() => {
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue(CATEGORIES);
    vi.mocked(categoriesApi.options).mockReset().mockResolvedValue({ icons: [], colors: [] });
    vi.mocked(categoriesApi.update).mockReset();
    vi.mocked(categoriesApi.delete).mockReset();
    vi.mocked(categoriesApi.usage).mockReset();
  });

  it('shows exact matches first when typing', async () => {
    const user = userEvent.setup();
    renderWithClient(<CategoryCombobox value="" onChange={vi.fn()} />);
    await user.click(screen.getByRole('combobox'));
    await user.type(screen.getByRole('combobox'), 'SIP');

    await waitFor(() => {
      expect(screen.getByText('SIP')).toBeInTheDocument();
    });
  });

  it('offers fuzzy "did you mean" suggestions for a near-miss', async () => {
    const user = userEvent.setup();
    renderWithClient(<CategoryCombobox value="" onChange={vi.fn()} />);
    await user.click(screen.getByRole('combobox'));
    await user.type(screen.getByRole('combobox'), 'S.I.P.');

    await waitFor(() => {
      expect(screen.getByText(/did you mean/i)).toBeInTheDocument();
      expect(screen.getByText('SIP')).toBeInTheDocument();
    });
  });

  it('shows the create row last, only for genuinely new text', async () => {
    const user = userEvent.setup();
    renderWithClient(<CategoryCombobox value="" onChange={vi.fn()} />);
    await user.click(screen.getByRole('combobox'));
    await user.type(screen.getByRole('combobox'), 'Freelance Income');

    await waitFor(() => {
      expect(screen.getByText('Create "Freelance Income"')).toBeInTheDocument();
    });
  });

  it('does not show a create row for an exact existing match', async () => {
    const user = userEvent.setup();
    renderWithClient(<CategoryCombobox value="" onChange={vi.fn()} />);
    await user.click(screen.getByRole('combobox'));
    await user.type(screen.getByRole('combobox'), 'Groceries');

    await waitFor(() => {
      expect(screen.queryByText(/^Create "/)).not.toBeInTheDocument();
    });
  });

  it('resyncs the displayed value when the value prop changes externally', async () => {
    const { rerender } = renderWithClient(<CategoryCombobox value="Groceries" onChange={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByRole('combobox')).toHaveValue('Groceries');
    });

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    rerender(
      <QueryClientProvider client={queryClient}>
        <CategoryCombobox value="Investments" onChange={vi.fn()} />
      </QueryClientProvider>,
    );

    await waitFor(() => {
      expect(screen.getByRole('combobox')).toHaveValue('Investments');
    });
  });

  // Final-branch review, finding 4. Typed-but-unselected text stayed in the input after the
  // dropdown closed, so inside Ledger's edit modal the user saw "Fuel" in the field, saved, and
  // got the old category -- the field was showing something that had never been selected.
  it('discards typed-but-unselected text when the dropdown closes without a selection', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithClient(
      <div>
        <CategoryCombobox value="Groceries" onChange={onChange} />
        <button type="button">elsewhere</button>
      </div>,
    );

    await user.click(screen.getByRole('combobox'));
    await user.clear(screen.getByRole('combobox'));
    await user.type(screen.getByRole('combobox'), 'Fuel');
    expect(screen.getByRole('combobox')).toHaveValue('Fuel');

    await user.click(screen.getByText('elsewhere'));

    await waitFor(() => {
      expect(screen.getByRole('combobox')).toHaveValue('Groceries');
    });
    expect(onChange).not.toHaveBeenCalled();
  });

  // Final-branch review, finding 7.
  it('says so when the category list failed to load, instead of looking empty', async () => {
    vi.mocked(categoriesApi.list).mockRejectedValue(new Error('boom'));
    renderWithClient(<CategoryCombobox value="" onChange={vi.fn()} />);

    expect(await screen.findByText(/couldn't load your categories/i)).toBeInTheDocument();
  });

  // Final-branch review, finding 6.
  it('associates an external label with its input via inputId', async () => {
    renderWithClient(
      <div>
        <label htmlFor="edit-txn-category">Category</label>
        <CategoryCombobox inputId="edit-txn-category" value="" onChange={vi.fn()} />
      </div>,
    );

    expect(screen.getByLabelText('Category')).toBe(screen.getByRole('combobox'));
  });

  // Final-branch review, finding 2: rename and delete were fully built but unreachable from any
  // page. These are the affordances that make PATCH/DELETE /categories reachable at all.
  describe('per-category edit and delete affordances', () => {
    it('offers them for user-created categories only, never for system ones', async () => {
      const user = userEvent.setup();
      renderWithClient(<CategoryCombobox value="" onChange={vi.fn()} />);
      await user.click(screen.getByRole('combobox'));

      expect(await screen.findByLabelText('Edit SIP')).toBeInTheDocument();
      expect(screen.getByLabelText('Delete SIP')).toBeInTheDocument();
      expect(screen.queryByLabelText('Edit Groceries')).not.toBeInTheDocument();
      expect(screen.queryByLabelText('Delete Groceries')).not.toBeInTheDocument();
      expect(screen.queryByLabelText('Edit Investments')).not.toBeInTheDocument();
      expect(screen.queryByLabelText('Delete Investments')).not.toBeInTheDocument();
    });

    it('opens the edit panel prefilled with the category and saves the rename', async () => {
      const user = userEvent.setup();
      const onChange = vi.fn();
      vi.mocked(categoriesApi.update).mockResolvedValue({
        id: '1', name: 'Monthly SIP', isSystem: false, icon: 'tag', color: 'gray',
      });
      renderWithClient(<CategoryCombobox value="SIP" onChange={onChange} />);

      await user.click(screen.getByRole('combobox'));
      await user.click(await screen.findByLabelText('Edit SIP'));

      const nameInput = await screen.findByPlaceholderText('Category name');
      expect(nameInput).toHaveValue('SIP');

      await user.clear(nameInput);
      await user.type(nameInput, 'Monthly SIP');
      await user.click(screen.getByText('Save'));

      await waitFor(() => {
        expect(categoriesApi.update).toHaveBeenCalledWith('1', {
          name: 'Monthly SIP', icon: 'tag', color: 'gray',
        });
      });
      // The renamed category was the field's own value, so the field follows the rename rather
      // than keeping a name that no longer exists.
      expect(onChange).toHaveBeenCalledWith('Monthly SIP');
    });

    it('opens the delete dialog and drops the category from the list on confirm', async () => {
      const user = userEvent.setup();
      const onChange = vi.fn();
      vi.mocked(categoriesApi.usage).mockResolvedValue({
        transactionCount: 0, hasBudget: false, ruleCount: 0,
      });
      vi.mocked(categoriesApi.delete).mockResolvedValue({} as never);
      vi.mocked(categoriesApi.list)
        .mockResolvedValueOnce(CATEGORIES)
        .mockResolvedValue(CATEGORIES.filter((c) => c.id !== '1'));

      renderWithClient(<CategoryCombobox value="SIP" onChange={onChange} />);

      await user.click(screen.getByRole('combobox'));
      await user.click(await screen.findByLabelText('Delete SIP'));

      await user.click(await screen.findByRole('button', { name: 'Delete' }));

      await waitFor(() => {
        expect(categoriesApi.delete).toHaveBeenCalledWith('1', undefined);
      });
      // It was the selected value, so the field clears rather than showing a dead category.
      await waitFor(() => expect(onChange).toHaveBeenCalledWith(''));

      await user.click(screen.getByRole('combobox'));
      await waitFor(() => {
        expect(screen.queryByLabelText('Delete SIP')).not.toBeInTheDocument();
      });
    });
  });
});
