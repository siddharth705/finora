import type { ReactNode } from 'react';
import { Text } from 'react-native';
import { render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { PremiumFeatureGate } from './PremiumFeatureGate';
import { entitlementsApi } from '../api/endpoints';
import type { EntitlementsDto } from '../api/endpoints';

jest.mock('../api/endpoints', () => ({ entitlementsApi: { mine: jest.fn() } }));

const mockedEntitlementsApi = entitlementsApi as jest.Mocked<typeof entitlementsApi>;

function renderGate(featureKey: string, fallback?: ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <PremiumFeatureGate featureKey={featureKey} fallback={fallback}>
        <Text>Secret premium content</Text>
      </PremiumFeatureGate>
    </QueryClientProvider>
  );
}

function entitlements(overrides: Partial<EntitlementsDto> = {}): EntitlementsDto {
  return { planCode: 'PREMIUM', planName: 'Premium', features: {}, ...overrides };
}

describe('PremiumFeatureGate (mobile)', () => {
  beforeEach(() => mockedEntitlementsApi.mine.mockReset());

  it('fails closed while loading', () => {
    mockedEntitlementsApi.mine.mockReturnValue(new Promise(() => {}));
    renderGate('FINO_AI');
    expect(screen.queryByText('Secret premium content')).toBeNull();
  });

  it('fails closed on error', async () => {
    mockedEntitlementsApi.mine.mockRejectedValue(new Error('network'));
    renderGate('FINO_AI');
    await waitFor(() => expect(screen.queryByText('Secret premium content')).toBeNull());
  });

  it('renders children when the feature is granted', async () => {
    mockedEntitlementsApi.mine.mockResolvedValue(entitlements({ features: { FINO_AI: true } }));
    renderGate('FINO_AI');
    expect(await screen.findByText('Secret premium content')).toBeTruthy();
  });

  it('renders the default upgrade prompt when the feature is absent', async () => {
    mockedEntitlementsApi.mine.mockResolvedValue(entitlements({ features: {} }));
    renderGate('FINO_AI');
    expect(await screen.findByText('This is a premium feature')).toBeTruthy();
  });

  it('renders a custom fallback when provided', async () => {
    mockedEntitlementsApi.mine.mockResolvedValue(entitlements({ features: {} }));
    renderGate('FINO_AI', <Text>Custom locked message</Text>);
    expect(await screen.findByText('Custom locked message')).toBeTruthy();
  });
});
