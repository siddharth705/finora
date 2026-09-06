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

  // Design spec §6.1 step 5: activation is only ever trusted from the backend's verified
  // RevenueCat webhook, never the client-side purchase call resolving -- so a real race exists
  // between purchasePlan() resolving and the webhook actually landing. Mirrors web's own
  // useActivationPoll (Billing.tsx) rather than a single invalidateQueries, which could otherwise
  // leave the Paywall showing the OLD (still-Free) plan with no explanation for as long as the
  // query's staleTime keeps that stale read looking "fresh".
  it('shows an activating message and polls until the purchase actually lands', async () => {
    mockedBillingApi.mySubscription
      .mockResolvedValueOnce({ planCode: 'FREE', hasBillingSubscription: false } as any) // initial load
      .mockResolvedValueOnce({ planCode: 'FREE', hasBillingSubscription: false } as any) // first poll: webhook hasn't landed yet
      .mockResolvedValue({ planCode: 'PLUS', hasBillingSubscription: true } as any); // second poll onward: activated
    mockedPurchasePlan.mockResolvedValue(undefined);
    renderScreen();

    fireEvent.press((await screen.findAllByText('Subscribe'))[0]);

    expect(await screen.findByText(/Activating your Plus plan/i)).toBeTruthy();
    // Once the poll sees the plan flip, the screen itself unmounts (SubscriptionScreen routes to
    // MySubscriptionScreen instead -- design spec §6.3/§6.4) -- observable proof the poll actually
    // succeeded, without coupling the test to exactly how many times invalidateQueries refetches.
    await waitFor(() => expect(screen.queryByText(/Activating/i)).toBeNull(), { timeout: 8000 });
  }, 12000);
});
