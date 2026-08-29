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
    await user.type(screen.getByPlaceholderText('Search categories'), 'SIP');

    await waitFor(() => {
      expect(screen.getByText('SIP')).toBeInTheDocument();
    });
  });

  it('offers fuzzy "did you mean" suggestions for a near-miss', async () => {
    const user = userEvent.setup();
    renderWithClient(<CategoryCombobox value="" onChange={vi.fn()} />);
    await user.click(screen.getByRole('combobox'));
    await user.type(screen.getByPlaceholderText('Search categories'), 'S.I.P.');

    await waitFor(() => {
      expect(screen.getByText(/did you mean/i)).toBeInTheDocument();
      expect(screen.getByText('SIP')).toBeInTheDocument();
    });
  });

  it('shows a persistent "New category" row before anything is typed', async () => {
    const user = userEvent.setup();
    renderWithClient(<CategoryCombobox value="" onChange={vi.fn()} />);
    await user.click(screen.getByRole('combobox'));

    expect(screen.getByText('New category')).toBeInTheDocument();
  });

  it('switches the create row to the typed text once it no longer matches anything', async () => {
    const user = userEvent.setup();
    renderWithClient(<CategoryCombobox value="" onChange={vi.fn()} />);
    await user.click(screen.getByRole('combobox'));
    await user.type(screen.getByPlaceholderText('Search categories'), 'Freelance Income');

    await waitFor(() => {
      expect(screen.getByText('Create "Freelance Income"')).toBeInTheDocument();
    });
  });

  it('does not show a create row for an exact existing match', async () => {
    const user = userEvent.setup();
    renderWithClient(<CategoryCombobox value="" onChange={vi.fn()} />);
    await user.click(screen.getByRole('combobox'));
    await user.type(screen.getByPlaceholderText('Search categories'), 'Groceries');

    await waitFor(() => {
      expect(screen.queryByText(/^Create "/)).not.toBeInTheDocument();
      expect(screen.queryByText('New category')).not.toBeInTheDocument();
    });
  });

  // The old plain <input> never lost focus when its dropdown closed. Swapping in a button+popover
  // introduced a real regression: selecting a row unmounts the popover (and whatever inside it had
  // focus), and without this, focus falls back to <body> -- a keyboard/screen-reader user loses
  // their place after every pick.
  it('returns focus to the trigger after picking a category', async () => {
    const user = userEvent.setup();
    renderWithClient(<CategoryCombobox value="" onChange={vi.fn()} />);
    const trigger = screen.getByRole('combobox');
    await user.click(trigger);
    await user.click(await screen.findByText('SIP'));

    expect(trigger).toHaveFocus();
  });

  it('closes on Escape and returns focus to the trigger, discarding any typed search text', async () => {
    const user = userEvent.setup();
    renderWithClient(<CategoryCombobox value="Groceries" onChange={vi.fn()} />);
    const trigger = screen.getByRole('combobox');
    await user.click(trigger);
    await user.type(screen.getByPlaceholderText('Search categories'), 'Fuel');

    await user.keyboard('{Escape}');

    expect(screen.queryByPlaceholderText('Search categories')).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();

    await user.click(trigger);
    expect(screen.getByPlaceholderText('Search categories')).toHaveValue('');
  });

  it('resyncs the trigger label when the value prop changes externally', async () => {
    const { rerender } = renderWithClient(<CategoryCombobox value="Groceries" onChange={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByRole('combobox')).toHaveTextContent('Groceries');
    });

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    rerender(
      <QueryClientProvider client={queryClient}>
        <CategoryCombobox value="Investments" onChange={vi.fn()} />
      </QueryClientProvider>,
    );

    await waitFor(() => {
      expect(screen.getByRole('combobox')).toHaveTextContent('Investments');
    });
  });

  // The old plain-input version could leave typed-but-unselected text stranded in the field after
  // the dropdown closed, so inside Ledger's edit modal the user saw "Fuel" in the field, saved,
  // and got the old category. The trigger button design removes that failure mode structurally --
  // the button only ever shows `value`, never the popover's own search text -- but the search
  // text itself should still not survive a close-without-selecting into the next open.
  it('does not let typed-but-unselected search text leak into the next open', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithClient(
      <div>
        <CategoryCombobox value="Groceries" onChange={onChange} />
        <button type="button">elsewhere</button>
      </div>,
    );

    await user.click(screen.getByRole('combobox'));
    await user.type(screen.getByPlaceholderText('Search categories'), 'Fuel');
    await user.click(screen.getByText('elsewhere'));

    expect(screen.getByRole('combobox')).toHaveTextContent('Groceries');
    expect(onChange).not.toHaveBeenCalled();

    await user.click(screen.getByRole('combobox'));
    expect(screen.getByPlaceholderText('Search categories')).toHaveValue('');
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
      // The rename path resolves through select(), which only clears the popover/search state --
      // not the edit-panel state itself. Regression coverage for a bug caught in review: the panel
      // has to be dismissed on this branch too, or the trigger button never comes back.
      expect(screen.queryByPlaceholderText('Category name')).not.toBeInTheDocument();
      expect(screen.getByRole('combobox')).toHaveFocus();
    });

    it('opens the delete dialog and drops the category from the list on confirm', async () => {
      const user = userEvent.setup();
      const onChange = vi.fn();
      vi.mocked(categoriesApi.usage).mockResolvedValue({
        transactionCount: 0, hasBudget: false, ruleCount: 0, learningRowCount: 0,
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
