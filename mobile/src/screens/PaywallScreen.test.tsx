import { render, screen, waitFor, fireEvent } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { PaywallScreen } from './PaywallScreen';
import { billingApi } from '../api/endpoints';
import { purchasePlan } from '../lib/revenueCat';

jest.mock('../api/endpoints', () => ({ billingApi: { mySubscription: jest.fn() } }));
jest.mock('../lib/revenueCat', () => ({ purchasePlan: jest.fn() }));

const mockedBillingApi = billingApi as jest.Mocked<typeof billingApi>;
const mockedPurchasePlan = purchasePlan as jest.MockedFunction<typeof purchasePlan>;

function renderScreen() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={queryClient}><PaywallScreen /></QueryClientProvider>);
}

describe('PaywallScreen', () => {
  beforeEach(() => {
    mockedBillingApi.mySubscription.mockResolvedValue({
      planCode: 'FREE', hasBillingSubscription: false,
    } as any);
  });

  it('shows both plans and purchases the tapped one', async () => {
    mockedPurchasePlan.mockResolvedValue(undefined);
    renderScreen();

    // Two "Subscribe" buttons render (one per plan) -- findByText is ambiguous here; the first
    // one is Plus, matching the PLANS array order in PaywallScreen.tsx.
    fireEvent.press((await screen.findAllByText('Subscribe'))[0]);

    await waitFor(() => expect(mockedPurchasePlan).toHaveBeenCalledWith('PLUS', 'MONTHLY'));
  });

  it('shows an error if the purchase fails', async () => {
    mockedPurchasePlan.mockRejectedValue(new Error('Purchase cancelled'));
    renderScreen();

    fireEvent.press((await screen.findAllByText('Subscribe'))[0]);

    expect(await screen.findByText('Purchase cancelled')).toBeTruthy();
  });
});
