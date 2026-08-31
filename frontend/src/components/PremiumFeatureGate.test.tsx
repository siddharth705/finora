import type { ReactNode } from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { PremiumFeatureGate } from './PremiumFeatureGate';
import { entitlementsApi } from '../api/endpoints';
import type { EntitlementsDto } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  entitlementsApi: { mine: vi.fn() },
}));

function renderGate(featureKey: string, fallback?: ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <PremiumFeatureGate featureKey={featureKey} fallback={fallback}>
        <p>Secret premium content</p>
      </PremiumFeatureGate>
    </QueryClientProvider>
  );
}

function entitlements(overrides: Partial<EntitlementsDto> = {}): EntitlementsDto {
  return { planCode: 'PREMIUM', planName: 'Premium', features: {}, ...overrides };
}

describe('PremiumFeatureGate', () => {
  beforeEach(() => {
    vi.mocked(entitlementsApi.mine).mockReset();
  });

  it('renders nothing while the entitlements query is loading -- fails closed, not open', () => {
    vi.mocked(entitlementsApi.mine).mockReturnValue(new Promise(() => {})); // never resolves
    const { container } = renderGate('FINO_AI');

    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing if the entitlements query errors -- fails closed, not open', async () => {
    vi.mocked(entitlementsApi.mine).mockRejectedValue(new Error('network'));
    const { container } = renderGate('FINO_AI');

    await new Promise((r) => setTimeout(r, 0));
    expect(container).toBeEmptyDOMElement();
  });

  it('renders the children when the feature is granted', async () => {
    vi.mocked(entitlementsApi.mine).mockResolvedValue(entitlements({ features: { FINO_AI: true } }));
    renderGate('FINO_AI');

    expect(await screen.findByText('Secret premium content')).toBeInTheDocument();
  });

  it('renders the default upgrade prompt, not the children, when the feature is absent from the map', async () => {
    vi.mocked(entitlementsApi.mine).mockResolvedValue(entitlements({ features: {} }));
    renderGate('FINO_AI');

    expect(await screen.findByText('This is a premium feature')).toBeInTheDocument();
    expect(screen.queryByText('Secret premium content')).not.toBeInTheDocument();
  });

  it('renders the default upgrade prompt when the feature is explicitly false', async () => {
    vi.mocked(entitlementsApi.mine).mockResolvedValue(entitlements({ features: { FINO_AI: false } }));
    renderGate('FINO_AI');

    expect(await screen.findByText('This is a premium feature')).toBeInTheDocument();
  });

  it('renders a custom fallback instead of the default prompt when one is provided', async () => {
    vi.mocked(entitlementsApi.mine).mockResolvedValue(entitlements({ features: {} }));
    renderGate('FINO_AI', <p>Custom locked message</p>);

    expect(await screen.findByText('Custom locked message')).toBeInTheDocument();
    expect(screen.queryByText('This is a premium feature')).not.toBeInTheDocument();
  });
});
