import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Budgets from './Budgets';
import { budgetsApi, categoriesApi, type CategoryOption } from '../api/endpoints';
import type { Budget } from '../types';

vi.mock('../api/endpoints', () => ({
  budgetsApi: { list: vi.fn(), upsert: vi.fn() },
  categoriesApi: { list: vi.fn() },
}));

function budget(overrides: Partial<Budget> = {}): Budget {
  return {
    id: 'b1',
    categoryId: 'c1',
    categoryName: 'Dining',
    monthlyLimit: 5000,
    spentThisMonth: 2000,
    ...overrides,
  } as Budget;
}

function category(overrides: Partial<CategoryOption> = {}): CategoryOption {
  return {
    id: 'c1',
    name: 'Dining',
    isSystem: true,
    icon: 'utensils',
    color: 'orange',
    ...overrides,
  };
}

function renderPage() {
  const queryClient = new QueryClient();
  return render(
    <QueryClientProvider client={queryClient}>
      <Budgets />
    </QueryClientProvider>
  );
}

describe('Budgets', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(categoriesApi.list).mockResolvedValue([]);
  });

  it('fetches categories alongside budgets, to look up each budget row\'s icon and color', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue([budget({ categoryId: 'c1', categoryName: 'Dining' })]);
    vi.mocked(categoriesApi.list).mockResolvedValue([category({ id: 'c1' })]);

    renderPage();

    await waitFor(() => expect(categoriesApi.list).toHaveBeenCalledTimes(1));
  });

  // The actual bug this page had: `budgets` started `[]`, which the render logic couldn't tell
  // apart from "genuinely no budgets set" -- so the EmptyState rendered immediately on every
  // mount, before the fetch had a chance to resolve, then popped to real content once it did.
  it('never shows the empty state while the initial fetch is still in flight', async () => {
    let resolveList: (b: Budget[]) => void;
    vi.mocked(budgetsApi.list).mockReturnValue(
      new Promise((resolve) => {
        resolveList = resolve;
      })
    );

    renderPage();

    // Still loading -- the empty-state copy must not be there yet, regardless of how much of the
    // delayed-skeleton window has elapsed.
    expect(screen.queryByText('No budgets set')).not.toBeInTheDocument();

    resolveList!([budget()]);
    await waitFor(() => expect(screen.getByText('Dining')).toBeInTheDocument());
    expect(screen.queryByText('No budgets set')).not.toBeInTheDocument();
  });

  it('shows the empty state once loading finishes with genuinely no budgets', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue([]);
    renderPage();

    expect(await screen.findByText('No budgets set')).toBeInTheDocument();
  });

  it('renders each budget once loaded', async () => {
    vi.mocked(budgetsApi.list).mockResolvedValue([
      budget({ id: 'b1', categoryName: 'Dining', monthlyLimit: 5000, spentThisMonth: 2000 }),
      budget({ id: 'b2', categoryName: 'Groceries', monthlyLimit: 8000, spentThisMonth: 8500 }),
    ]);
    renderPage();

    expect(await screen.findByText('Dining')).toBeInTheDocument();
    expect(screen.getByText('Groceries')).toBeInTheDocument();
  });

  it('surfaces a failed load as an error, distinct from the loading state', async () => {
    // Not asserting on the EmptyState here: a failed fetch still leaves `budgets` at its default
    // `[]`, so the empty-state copy shows alongside the error banner today -- pre-existing
    // behavior this fix doesn't touch (a full error-state pass is explicitly out of scope for
    // this roadmap, see its "Explicitly deferred" section). What this fix does guarantee is that
    // the error path also clears `loading`, so the page doesn't stay stuck showing a skeleton
    // forever on a failed request.
    vi.mocked(budgetsApi.list).mockRejectedValue(new Error('network error'));
    renderPage();

    await waitFor(() => expect(screen.getByText('Could not load budgets.')).toBeInTheDocument());
  });
});
