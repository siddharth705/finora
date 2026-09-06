// EXPO_PUBLIC_REVENUECAT_API_KEY is set globally in src/test/setup.ts -- revenueCat.ts throws at
// import time when it's missing, and setupFilesAfterEnv runs before this file's own imports.
import Purchases from 'react-native-purchases';
import { configureRevenueCat, purchasePlan } from './revenueCat';

// __esModule: true is required here -- without it, TS's default-import interop for `import
// Purchases from 'react-native-purchases'` resolves to the whole mock object (with `default`
// nested inside it) rather than unwrapping to the object below, and every mocked method call
// throws "Cannot read properties of undefined".
jest.mock('react-native-purchases', () => ({
  __esModule: true,
  default: {
    configure: jest.fn(),
    getOfferings: jest.fn(),
    purchasePackage: jest.fn(),
    restorePurchases: jest.fn(),
  },
}));

const mockedPurchases = Purchases as jest.Mocked<typeof Purchases>;

describe('configureRevenueCat', () => {
  it('configures with the real Fynora user id as appUserID, never anonymous', () => {
    configureRevenueCat('11111111-1111-1111-1111-111111111111');

    expect(mockedPurchases.configure).toHaveBeenCalledWith(
      expect.objectContaining({ appUserID: '11111111-1111-1111-1111-111111111111' })
    );
  });
});

describe('purchasePlan', () => {
  beforeEach(() => mockedPurchases.getOfferings.mockReset());

  it('purchases the package matching the requested plan and cycle', async () => {
    const targetPackage = { identifier: 'plus_monthly', product: { identifier: 'plus_monthly' } };
    mockedPurchases.getOfferings.mockResolvedValue({
      current: { availablePackages: [targetPackage] },
    } as any);
    mockedPurchases.purchasePackage.mockResolvedValue({} as any);

    await purchasePlan('PLUS', 'MONTHLY');

    expect(mockedPurchases.purchasePackage).toHaveBeenCalledWith(targetPackage);
  });

  it('throws a clear error when no offering package matches the plan/cycle', async () => {
    mockedPurchases.getOfferings.mockResolvedValue({ current: { availablePackages: [] } } as any);

    await expect(purchasePlan('PREMIUM', 'YEARLY')).rejects.toThrow(/no.*offering/i);
  });
});
