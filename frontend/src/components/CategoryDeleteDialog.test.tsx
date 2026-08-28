import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { CategoryDeleteDialog } from './CategoryDeleteDialog';
import { categoriesApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  categoriesApi: { usage: vi.fn(), delete: vi.fn(), list: vi.fn() },
}));

const CATEGORY = { id: '1', name: 'Mutual Fund SIP', isSystem: false, icon: 'tag', color: 'gray' };

// Both this component and the one it renders read/invalidate the shared ['categories'] react-query
// cache, so a provider is required. Fresh client per render keeps tests isolated.
function renderWithClient(ui: ReactElement) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>);
}

describe('CategoryDeleteDialog', () => {
  beforeEach(() => {
    vi.mocked(categoriesApi.list).mockResolvedValue([
      CATEGORY,
      { id: '2', name: 'SIP', isSystem: false, icon: 'tag', color: 'gray' },
    ]);
  });

  it('shows the usage summary before allowing delete', async () => {
    vi.mocked(categoriesApi.usage).mockResolvedValue({ transactionCount: 12, hasBudget: true, ruleCount: 1 });
    renderWithClient(<CategoryDeleteDialog category={CATEGORY} onDeleted={vi.fn()} onCancel={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByText(/12/)).toBeInTheDocument();
      expect(screen.getByText(/1 budget/i)).toBeInTheDocument();
      expect(screen.getByText(/1 rule/i)).toBeInTheDocument();
    });
  });

  it('disables the confirm button until a reassignment target is picked, when there are dependents', async () => {
    vi.mocked(categoriesApi.usage).mockResolvedValue({ transactionCount: 5, hasBudget: false, ruleCount: 0 });
    renderWithClient(<CategoryDeleteDialog category={CATEGORY} onDeleted={vi.fn()} onCancel={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /delete/i })).toBeDisabled();
    });
  });

  it('allows immediate delete with no target when there are zero dependents', async () => {
    const user = userEvent.setup();
    vi.mocked(categoriesApi.usage).mockResolvedValue({ transactionCount: 0, hasBudget: false, ruleCount: 0 });
    vi.mocked(categoriesApi.delete).mockResolvedValue({} as any);
    const onDeleted = vi.fn();
    renderWithClient(<CategoryDeleteDialog category={CATEGORY} onDeleted={onDeleted} onCancel={vi.fn()} />);

    const deleteButton = await screen.findByRole('button', { name: /delete/i });
    await waitFor(() => expect(deleteButton).toBeEnabled());
    await user.click(deleteButton);

    await waitFor(() => {
      expect(categoriesApi.delete).toHaveBeenCalledWith('1', undefined);
      expect(onDeleted).toHaveBeenCalled();
    });
  });

  it('shows an error and does not call onDeleted when delete fails', async () => {
    const user = userEvent.setup();
    vi.mocked(categoriesApi.usage).mockResolvedValue({ transactionCount: 0, hasBudget: false, ruleCount: 0 });
    vi.mocked(categoriesApi.delete).mockRejectedValue({
      response: { data: { message: 'A dependent was added to this category.' } },
    });
    const onDeleted = vi.fn();
    renderWithClient(<CategoryDeleteDialog category={CATEGORY} onDeleted={onDeleted} onCancel={vi.fn()} />);

    const deleteButton = await screen.findByRole('button', { name: /delete/i });
    await waitFor(() => expect(deleteButton).toBeEnabled());
    await user.click(deleteButton);

    await waitFor(() => {
      expect(screen.getByText(/a dependent was added to this category/i)).toBeInTheDocument();
    });
    expect(onDeleted).not.toHaveBeenCalled();
  });
});
