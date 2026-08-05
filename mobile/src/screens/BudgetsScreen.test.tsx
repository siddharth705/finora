import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BudgetsScreen } from './BudgetsScreen';
import { budgetsApi, categoriesApi } from '../api/endpoints';
import type { Budget } from '../types';

jest.mock('../api/endpoints', () => ({
  budgetsApi: { list: jest.fn(), upsert: jest.fn() },
  categoriesApi: { list: jest.fn() },
}));

const api = budgetsApi as jest.Mocked<typeof budgetsApi>;
const categories = categoriesApi as jest.Mocked<typeof categoriesApi>;

const budgets: Budget[] = [
  { id: 'b-1', categoryId: 'c-1', categoryName: 'Groceries', monthlyLimit: 10000, spentThisMonth: 4000 },
  { id: 'b-2', categoryId: 'c-2', categoryName: 'Dining', monthlyLimit: 5000, spentThisMonth: 6200 },
];

function renderScreen() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <BudgetsScreen />
    </QueryClientProvider>
  );
}

async function settle() {
  await act(async () => {});
}

describe('BudgetsScreen', () => {
  beforeEach(() => {
    api.list.mockReset().mockResolvedValue(budgets);
    api.upsert.mockReset().mockResolvedValue(budgets[0]);
    categories.list.mockReset().mockResolvedValue([
      { id: 'c-1', name: 'Groceries', isSystem: true },
      { id: 'c-2', name: 'Dining', isSystem: true },
    ]);
  });

  it('states what is left, in words, for a budget still under its limit', async () => {
    renderScreen();

    expect(await screen.findByText('₹6,000 left this month')).toBeTruthy();
  });

  // Overspending is the thing a budget screen exists to surface, and a progress bar alone can only
  // show "full" -- 100% and 124% look identical.
  it('says how far over budget an overspent category is', async () => {
    renderScreen();

    expect(await screen.findByText('₹1,200 over budget')).toBeTruthy();
    expect(
      screen.getByLabelText(/Dining: ₹6,200 spent of ₹5,000. ₹1,200 over budget/)
    ).toBeTruthy();
  });

  it('saves a budget for the category chosen from the picker', async () => {
    renderScreen();
    await screen.findByText('₹6,000 left this month');

    fireEvent.press(screen.getByLabelText('Choose a category'));
    await settle();
    // By role: the category also appears as a budget row below, which is not a button.
    fireEvent.press(screen.getByRole('button', { name: 'Groceries' }));
    await settle();
    fireEvent.changeText(screen.getByLabelText(/Monthly limit/i), '12000');
    fireEvent.press(screen.getByText('Set Budget'));
    await settle();

    await waitFor(() => expect(api.upsert).toHaveBeenCalledWith('Groceries', 12000));
  });

  // The web page takes the category as free text, so a typo silently creates a budget nothing is
  // ever filed under. Picking from the real category list is what prevents that.
  it('refuses to save without a category', async () => {
    renderScreen();
    await screen.findByText('₹6,000 left this month');

    fireEvent.changeText(screen.getByLabelText(/Monthly limit/i), '12000');
    fireEvent.press(screen.getByText('Set Budget'));
    await settle();

    expect(api.upsert).not.toHaveBeenCalled();
    expect(screen.getByText('Pick a category first.')).toBeTruthy();
  });

  it('refuses a limit that is not a positive number', async () => {
    renderScreen();
    await screen.findByText('₹6,000 left this month');

    fireEvent.press(screen.getByLabelText('Choose a category'));
    await settle();
    // By role: the category also appears as a budget row below, which is not a button.
    fireEvent.press(screen.getByRole('button', { name: 'Groceries' }));
    await settle();

    for (const bad of ['0', '-1', 'abc']) {
      fireEvent.changeText(screen.getByLabelText(/Monthly limit/i), bad);
      fireEvent.press(screen.getByText('Set Budget'));
      await settle();
    }

    expect(api.upsert).not.toHaveBeenCalled();
  });

  it('says so plainly when no budgets are set', async () => {
    api.list.mockReset().mockResolvedValue([]);
    renderScreen();

    expect(await screen.findByText(/No budgets set yet/)).toBeTruthy();
  });
});
