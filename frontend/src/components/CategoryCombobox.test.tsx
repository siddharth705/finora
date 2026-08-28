import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { CategoryCombobox } from './CategoryCombobox';
import { categoriesApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  categoriesApi: { list: vi.fn() },
}));

const CATEGORIES = [
  { id: '1', name: 'SIP', isSystem: false, icon: 'tag', color: 'gray' },
  { id: '2', name: 'Investments', isSystem: true, icon: 'trending-up', color: 'teal' },
  { id: '3', name: 'Groceries', isSystem: true, icon: 'shopping-cart', color: 'green' },
];

describe('CategoryCombobox', () => {
  beforeEach(() => {
    vi.mocked(categoriesApi.list).mockResolvedValue(CATEGORIES);
  });

  it('shows exact matches first when typing', async () => {
    const user = userEvent.setup();
    render(<CategoryCombobox value="" onChange={vi.fn()} />);
    await user.click(screen.getByRole('combobox'));
    await user.type(screen.getByRole('combobox'), 'SIP');

    await waitFor(() => {
      expect(screen.getByText('SIP')).toBeInTheDocument();
    });
  });

  it('offers fuzzy "did you mean" suggestions for a near-miss', async () => {
    const user = userEvent.setup();
    render(<CategoryCombobox value="" onChange={vi.fn()} />);
    await user.click(screen.getByRole('combobox'));
    await user.type(screen.getByRole('combobox'), 'S.I.P.');

    await waitFor(() => {
      expect(screen.getByText(/did you mean/i)).toBeInTheDocument();
      expect(screen.getByText('SIP')).toBeInTheDocument();
    });
  });

  it('shows the create row last, only for genuinely new text', async () => {
    const user = userEvent.setup();
    render(<CategoryCombobox value="" onChange={vi.fn()} />);
    await user.click(screen.getByRole('combobox'));
    await user.type(screen.getByRole('combobox'), 'Freelance Income');

    await waitFor(() => {
      expect(screen.getByText('Create "Freelance Income"')).toBeInTheDocument();
    });
  });

  it('does not show a create row for an exact existing match', async () => {
    const user = userEvent.setup();
    render(<CategoryCombobox value="" onChange={vi.fn()} />);
    await user.click(screen.getByRole('combobox'));
    await user.type(screen.getByRole('combobox'), 'Groceries');

    await waitFor(() => {
      expect(screen.queryByText(/^Create "/)).not.toBeInTheDocument();
    });
  });

  it('resyncs the displayed value when the value prop changes externally', async () => {
    const { rerender } = render(<CategoryCombobox value="Groceries" onChange={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByRole('combobox')).toHaveValue('Groceries');
    });

    rerender(<CategoryCombobox value="Investments" onChange={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByRole('combobox')).toHaveValue('Investments');
    });
  });
});
