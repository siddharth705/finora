import { render, screen, waitFor, fireEvent } from '@testing-library/react-native';
import { Linking } from 'react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MySubscriptionScreen } from './MySubscriptionScreen';
import { billingApi } from '../api/endpoints';
import { restorePurchases } from '../lib/revenueCat';

jest.mock('../api/endpoints', () => ({ billingApi: { mySubscription: jest.fn() } }));
jest.mock('../lib/revenueCat', () => ({ restorePurchases: jest.fn() }));

const mockedBillingApi = billingApi as jest.Mocked<typeof billingApi>;
const mockedRestorePurchases = restorePurchases as jest.MockedFunction<typeof restorePurchases>;

function renderScreen() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={queryClient}><MySubscriptionScreen /></QueryClientProvider>);
}

describe('MySubscriptionScreen', () => {
  it('shows a read-only view with no controls for a Razorpay-owned subscription', async () => {
    mockedBillingApi.mySubscription.mockResolvedValue({
      planCode: 'PLUS', planName: 'Plus', hasBillingSubscription: true, paymentProvider: 'RAZORPAY',
    } as any);
    renderScreen();

    expect(await screen.findByText(/managed on web/i)).toBeTruthy();
    expect(screen.queryByText('Manage subscription')).toBeNull();
    expect(screen.queryByText('Restore Purchases')).toBeNull();
  });

  it('shows Manage subscription and Restore Purchases for a RevenueCat-owned subscription', async () => {
    mockedBillingApi.mySubscription.mockResolvedValue({
      planCode: 'PREMIUM', planName: 'Premium', hasBillingSubscription: true, paymentProvider: 'REVENUECAT',
    } as any);
    renderScreen();

    expect(await screen.findByText('Manage subscription')).toBeTruthy();
    expect(await screen.findByText('Restore Purchases')).toBeTruthy();
  });

  it('opens the OS subscription settings when Manage subscription is tapped', async () => {
    mockedBillingApi.mySubscription.mockResolvedValue({
      planCode: 'PREMIUM', planName: 'Premium', hasBillingSubscription: true, paymentProvider: 'REVENUECAT',
    } as any);
    const openURLSpy = jest.spyOn(Linking, 'openURL').mockResolvedValue(true);
    renderScreen();

    fireEvent.press(await screen.findByText('Manage subscription'));

    await waitFor(() => expect(openURLSpy).toHaveBeenCalled());
  });

  it('calls restorePurchases and refetches when Restore Purchases is tapped', async () => {
    mockedBillingApi.mySubscription.mockResolvedValue({
      planCode: 'FREE', hasBillingSubscription: false, paymentProvider: null,
    } as any);
    mockedRestorePurchases.mockResolvedValue(undefined);
    renderScreen();

    fireEvent.press(await screen.findByText('Restore Purchases'));

    await waitFor(() => expect(mockedRestorePurchases).toHaveBeenCalled());
  });
});
