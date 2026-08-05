import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { GoalsScreen } from './GoalsScreen';
import { goalsApi } from '../api/endpoints';
import type { Goal } from '../types';

jest.mock('../api/endpoints', () => ({
  goalsApi: {
    list: jest.fn(),
    create: jest.fn(),
    addContribution: jest.fn(),
    remove: jest.fn(),
  },
}));

const api = goalsApi as jest.Mocked<typeof goalsApi>;

const goal: Goal = {
  id: 'goal-1',
  name: 'Emergency fund',
  targetAmount: 100000,
  currentAmount: 40000,
  targetDate: '2027-03-01',
};

function renderScreen() {
  // gcTime 0 so the cache is collected on unmount -- see StatementHistoryScreen.test's own note.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <GoalsScreen />
    </QueryClientProvider>
  );
}

async function settle() {
  await act(async () => {});
}

async function openContributionSheet() {
  fireEvent.press(await screen.findByLabelText('Add contribution to Emergency fund'));
  await settle();
}

/**
 * The web page collects this amount with `window.prompt()`, which does not exist in React Native --
 * the whole reason this sheet exists. These tests are the proof the substitution actually works,
 * since a missing `prompt` fails silently at runtime rather than at build time.
 */
describe('GoalsScreen — contributing to a goal', () => {
  beforeEach(() => {
    api.list.mockReset().mockResolvedValue([goal]);
    api.addContribution.mockReset().mockResolvedValue(goal);
  });

  it('opens a numeric amount field instead of a browser prompt', async () => {
    renderScreen();

    await openContributionSheet();

    const field = await screen.findByLabelText('Amount in rupees');
    // decimal-pad, not the default keyboard: this field can only ever take a number.
    expect(field.props.keyboardType).toBe('decimal-pad');
    // Names the goal being funded -- the prompt it replaces could not. Two matches: the card
    // behind the sheet, and the sheet's own subtitle.
    expect(screen.getAllByText('Emergency fund')).toHaveLength(2);
  });

  it('records the typed amount against the right goal', async () => {
    renderScreen();
    await openContributionSheet();

    fireEvent.changeText(screen.getByLabelText('Amount in rupees'), '2500');
    fireEvent.press(screen.getByText('Add'));
    await settle();

    await waitFor(() => expect(api.addContribution).toHaveBeenCalledWith('goal-1', 2500));
  });

  it('refuses an amount that is not a positive number', async () => {
    renderScreen();
    await openContributionSheet();

    for (const bad of ['0', '-50', 'abc', '']) {
      fireEvent.changeText(screen.getByLabelText('Amount in rupees'), bad);
      fireEvent.press(screen.getByText('Add'));
      await settle();
    }

    // `prompt()` returned a raw string and the web page's own parse let "12abc" through as 12.
    expect(api.addContribution).not.toHaveBeenCalled();
  });

  it('keeps the sheet open with the error when the request fails', async () => {
    api.addContribution.mockReset().mockRejectedValue(
      Object.assign(new Error('failed'), {
        isAxiosError: true,
        response: { status: 400, data: { message: 'Contribution exceeds the target.' } },
      })
    );
    renderScreen();
    await openContributionSheet();

    fireEvent.changeText(screen.getByLabelText('Amount in rupees'), '999999');
    fireEvent.press(screen.getByText('Add'));
    await settle();

    expect(await screen.findByText('Contribution exceeds the target.')).toBeTruthy();
    // Still open -- closing it would lose the amount and hide why it failed.
    expect(screen.getByLabelText('Amount in rupees')).toBeTruthy();
  });

  /**
   * The request is already on its way when Cancel is tapped, so closing the sheet would let the
   * contribution land while the user last saw themselves cancel it. Money must not move after an
   * apparent cancel.
   */
  it('cannot be cancelled once the contribution is in flight', async () => {
    let release!: () => void;
    api.addContribution.mockReset().mockReturnValue(
      new Promise((resolve) => { release = () => resolve(goal); })
    );
    renderScreen();
    await openContributionSheet();

    fireEvent.changeText(screen.getByLabelText('Amount in rupees'), '2500');
    fireEvent.press(screen.getByText('Add'));
    await settle();

    const cancel = screen.getByRole('button', { name: 'Cancel' });
    expect(cancel.props.accessibilityState.disabled).toBe(true);
    fireEvent.press(cancel);
    await settle();

    // Still open, still showing the request in progress.
    expect(screen.getByLabelText('Amount in rupees')).toBeTruthy();
    expect(screen.getByText('Saving…')).toBeTruthy();

    await act(async () => { release(); });
  });

  // A `submitting` flag disables the button only on the next render, so two taps dispatched in the
  // same frame both get through and the goal is funded twice.
  it('records one contribution for a double tap', async () => {
    renderScreen();
    await openContributionSheet();
    fireEvent.changeText(screen.getByLabelText('Amount in rupees'), '2500');

    const add = screen.getByText('Add');
    fireEvent.press(add);
    fireEvent.press(add);
    await settle();

    await waitFor(() => expect(api.addContribution).toHaveBeenCalledTimes(1));
  });

  it('starts empty on each open rather than inheriting the last amount', async () => {
    renderScreen();
    await openContributionSheet();
    fireEvent.changeText(screen.getByLabelText('Amount in rupees'), '2500');
    fireEvent.press(screen.getByText('Cancel'));
    await settle();

    await openContributionSheet();

    expect(screen.getByLabelText('Amount in rupees').props.value).toBe('');
  });
});

describe('GoalsScreen — the list', () => {
  beforeEach(() => {
    api.list.mockReset().mockResolvedValue([goal]);
  });

  it('states progress in words, not only as a bar', async () => {
    renderScreen();

    // The bar itself is hidden from assistive tech; this label is the accessible representation.
    expect(
      await screen.findByLabelText(/Emergency fund: ₹40,000 of ₹1,00,000, 40 percent complete/)
    ).toBeTruthy();
  });

  it('renders a target date on the day it names', async () => {
    renderScreen();

    // Not 28 Feb: a LocalDate parsed through UTC shifts a day for viewers behind UTC.
    expect(await screen.findByText(/target.*1 Mar 2027/)).toBeTruthy();
  });

  it('says so plainly when there are no goals', async () => {
    api.list.mockReset().mockResolvedValue([]);
    renderScreen();

    expect(await screen.findByText(/No goals yet/)).toBeTruthy();
  });
});
