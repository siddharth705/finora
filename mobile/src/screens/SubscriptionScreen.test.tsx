import { render, screen } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SubscriptionScreen } from './SubscriptionScreen';
import { billingApi } from '../api/endpoints';

jest.mock('../api/endpoints', () => ({ billingApi: { mySubscription: jest.fn() } }));
jest.mock('./PaywallScreen', () => ({ PaywallScreen: () => { const { Text } = require('react-native'); return <Text>PAYWALL</Text>; } }));
jest.mock('./MySubscriptionScreen', () => ({ MySubscriptionScreen: () => { const { Text } = require('react-native'); return <Text>MY_SUBSCRIPTION</Text>; } }));

const mockedBillingApi = billingApi as jest.Mocked<typeof billingApi>;

function renderScreen() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={queryClient}><SubscriptionScreen /></QueryClientProvider>);
}

describe('SubscriptionScreen', () => {
  it('shows the Paywall when the user has no billing subscription', async () => {
    mockedBillingApi.mySubscription.mockResolvedValue({ hasBillingSubscription: false } as any);
    renderScreen();
    expect(await screen.findByText('PAYWALL')).toBeTruthy();
  });

  it('shows My Subscription when the user already has one', async () => {
    mockedBillingApi.mySubscription.mockResolvedValue({ hasBillingSubscription: true } as any);
    renderScreen();
    expect(await screen.findByText('MY_SUBSCRIPTION')).toBeTruthy();
  });
});
