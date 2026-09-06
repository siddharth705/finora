import type { ComponentType, ReactNode } from 'react';
import { render, screen } from '@testing-library/react-native';
import { Text } from 'react-native';
import { AppTabs } from './AppTabs';
import { TourTargetProvider, useTourTarget } from '../onboarding/TourTargetRegistry';

// Same pre-existing ESM/native-stack gap RootNavigator.test.tsx's own mock comment documents --
// @react-navigation/bottom-tabs' published "main" is the same kind of unbuilt ESM file. Mocked
// here, scoped to this file, for the same reason.
jest.mock('@react-navigation/bottom-tabs', () => ({
  createBottomTabNavigator: () => ({
    // Exercises the real tabBarIcon function (where the ref-registration this test is actually
    // about lives) for each of AppTabs' own <Tab.Screen> children, the same way the real
    // navigator would call it once per tab -- without that, this mock would render AppTabs
    // without ever calling the code under test. Untyped/plain on purpose: jest.mock's factory is
    // hoisted above imports, and an inline TS parameter type here confuses the "out-of-scope
    // variable" check with a false positive on the type name itself.
    Navigator: (props: any) => {
      const { Children, isValidElement, Fragment, createElement } = require('react');
      const { Text } = require('react-native');
      const names = Children.toArray(props.children)
        .filter((child: any) => isValidElement(child))
        .map((child: any) => child.props.name);
      return createElement(
        Fragment,
        null,
        names.map((name: string) => {
          const options = props.screenOptions ? props.screenOptions({ route: { name } }) : {};
          const icon = typeof options.tabBarIcon === 'function'
            ? options.tabBarIcon({ focused: false, color: '#000', size: 20 })
            : null;
          return createElement(Text, { key: name }, icon);
        })
      );
    },
    Screen: () => null,
  }),
}));

jest.mock('@react-navigation/native-stack', () => ({
  createNativeStackNavigator: () => ({
    Navigator: ({ children }: { children: ReactNode }) => children,
    Screen: ({ component: Component }: { component: ComponentType }) => <Component />,
  }),
}));

jest.mock('../theme', () => ({
  useTheme: () => ({ bg: '#fff', primary: '#000', card: '#fff', ink: '#000', border: '#ccc', muted: '#888' }),
}));

// Every screen AppTabs mounts (directly or via MoreNavigator) is stubbed -- this test is about
// the tab-icon ref registration AppTabs itself owns, not any individual screen's rendering.
jest.mock('../screens/DashboardScreen', () => ({ DashboardScreen: () => null }));
jest.mock('../screens/LedgerScreen', () => ({ LedgerScreen: () => null }));
jest.mock('../screens/AccountsScreen', () => ({ AccountsScreen: () => null }));
jest.mock('../screens/StatementHistoryScreen', () => ({ StatementHistoryScreen: () => null }));
jest.mock('../screens/import/ImportScreen', () => ({ ImportScreen: () => null }));
jest.mock('../screens/MoreScreen', () => ({ MoreScreen: () => null }));
jest.mock('../screens/CategoryReviewScreen', () => ({ CategoryReviewScreen: () => null }));
jest.mock('../screens/BudgetsScreen', () => ({ BudgetsScreen: () => null }));
jest.mock('../screens/SubscriptionScreen', () => ({ SubscriptionScreen: () => null }));
jest.mock('../screens/GoalsScreen', () => ({ GoalsScreen: () => null }));
jest.mock('../screens/ReportsScreen', () => ({ ReportsScreen: () => null }));
jest.mock('../screens/InsightsScreen', () => ({ InsightsScreen: () => null }));
jest.mock('../screens/InvestmentsScreen', () => ({ InvestmentsScreen: () => null }));
jest.mock('../screens/ProfileScreen', () => ({ ProfileScreen: () => null }));
jest.mock('../screens/ReferralsScreen', () => ({ ReferralsScreen: () => null }));
jest.mock('../screens/SettingsScreen', () => ({ SettingsScreen: () => null }));
jest.mock('../screens/SupportTicketDetailScreen', () => ({ SupportTicketDetailScreen: () => null }));
jest.mock('../screens/SupportTicketsScreen', () => ({ SupportTicketsScreen: () => null }));
jest.mock('../screens/settings/VerifyEmailChangeScreen', () => ({ VerifyEmailChangeScreen: () => null }));

function TargetProbe({ tourKey }: { tourKey: string }) {
  const target = useTourTarget(tourKey);
  return <Text testID={`probe-${tourKey}`}>{target ? 'found' : 'missing'}</Text>;
}

describe('AppTabs tour target registration', () => {
  it('registers home/transactions/import refs on a real TourTargetProvider, without throwing', () => {
    render(
      <TourTargetProvider>
        <AppTabs />
        <TargetProbe tourKey="home" />
        <TargetProbe tourKey="transactions" />
        <TargetProbe tourKey="import" />
      </TourTargetProvider>
    );

    expect(screen.getByTestId('probe-home')).toHaveTextContent('found');
    expect(screen.getByTestId('probe-transactions')).toHaveTextContent('found');
    expect(screen.getByTestId('probe-import')).toHaveTextContent('found');
  });

  it('throws a clear error if rendered without a TourTargetProvider (this is why RootNavigator always wraps one)', () => {
    // console.error is expected here -- React logs the thrown error before this assertion sees it.
    const consoleErrorSpy = jest.spyOn(console, 'error').mockImplementation(() => {});
    expect(() => render(<AppTabs />)).toThrow('useRegisterTourTarget must be used within TourTargetProvider');
    consoleErrorSpy.mockRestore();
  });
});
