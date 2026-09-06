import type { ComponentType, ReactNode } from 'react';
import { render, screen } from '@testing-library/react-native';
import { RootNavigator } from './RootNavigator';
import { useAuth } from '../context/AuthContext';
import { useOnboardingStep } from '../onboarding/OnboardingStepContext';

jest.mock('../context/AuthContext', () => ({
  useAuth: jest.fn(),
}));

jest.mock('../theme', () => ({
  useTheme: () => ({ bg: '#fff', primary: '#000', card: '#fff', ink: '#000', border: '#ccc', muted: '#888' }),
  useThemeSetting: () => ({ resolved: 'light' }),
}));

// @react-navigation/native's own real NavigationContainer/useNavigationContainerRef pull in
// enough of the same unbuilt-ESM/native-stack machinery that this test hits the same pre-existing
// gap the native-stack mock below exists for -- see that mock's own comment. Stubbed to the
// minimum RootNavigator itself actually calls: NavigationContainer as a pass-through wrapper,
// useNavigationContainerRef as a plain ref, DefaultTheme/DarkTheme as the two plain objects
// RootNavigator spreads into its own navTheme.
jest.mock('@react-navigation/native', () => ({
  NavigationContainer: ({ children }: { children: ReactNode }) => children,
  useNavigationContainerRef: () => ({ current: null }),
  DefaultTheme: { colors: {}, fonts: {} },
  DarkTheme: { colors: {}, fonts: {} },
}));

jest.mock('./useAuthStackInitialRoute', () => ({
  useAuthStackInitialRoute: () => 'AuthEntry',
}));

jest.mock('./useEmailChangeDeepLink', () => ({
  useEmailChangeDeepLink: () => ({ onNavigationReady: jest.fn() }),
}));

jest.mock('./useNavigationStatePersistence', () => ({
  useNavigationStatePersistence: () => ({ isReady: true, initialState: undefined, onStateChange: jest.fn() }),
}));

// @react-navigation/native-stack's published "main" entry is an unbuilt ESM file, which this
// project's Jest/Babel pipeline can't load directly (a pre-existing gap, unrelated to this
// feature -- no prior test exercised this import path since RootNavigator had no test file
// before this one). Mocked here, scoped to this file only, rather than touching the shared Jest
// config: Navigator renders its children and Screen renders its component unconditionally, which
// is enough to prove which of RootNavigator's own top-level branches (Auth/VerifyPhone/
// Onboarding/AppTabs) is selected -- the thing this test file is actually about.
jest.mock('@react-navigation/native-stack', () => ({
  createNativeStackNavigator: () => ({
    Navigator: ({ children }: { children: ReactNode }) => children,
    Screen: ({ component: Component }: { component: ComponentType }) => <Component />,
  }),
}));

jest.mock('./AppTabs', () => ({
  AppTabs: () => {
    const { Text } = require('react-native');
    return <Text testID="app-tabs">AppTabs</Text>;
  },
}));

// Same reasoning as AppTabs above: RootNavigator.test.tsx is about which top-level branch gets
// selected, not OnboardingNavigator's own screens (which pull in real Button/theme styling this
// file's minimal '../theme' mock doesn't provide).
jest.mock('../onboarding/OnboardingNavigator', () => ({
  OnboardingNavigator: () => {
    const { Text } = require('react-native');
    return <Text testID="onboarding-navigator">OnboardingNavigator</Text>;
  },
}));

jest.mock('../onboarding/OnboardingStepContext', () => ({
  useOnboardingStep: jest.fn(),
}));

jest.mock('../onboarding/TourOverlay', () => ({
  TourOverlay: () => {
    const { Text } = require('react-native');
    return <Text testID="tour-overlay">TourOverlay</Text>;
  },
}));

jest.mock('../onboarding/TourTargetRegistry', () => ({
  TourTargetProvider: ({ children }: { children: ReactNode }) => children,
}));

// RootNavigator imports these five screens directly (not lazily), and every one of them pulls in
// real styling that reads spacing/radius off '../theme' at module-load time -- which the mock
// above doesn't provide (it only covers what RootNavigator itself calls, useTheme/
// useThemeSetting). Stubbed for the same reason AppTabs is: this test is about which top-level
// branch RootNavigator selects, not any individual screen's own rendering.
jest.mock('../screens/AuthEntryScreen', () => ({ AuthEntryScreen: () => null }));
jest.mock('../screens/LoginScreen', () => ({ LoginScreen: () => null }));
jest.mock('../screens/RegisterScreen', () => ({ RegisterScreen: () => null }));
jest.mock('../screens/ForgotPasswordScreen', () => ({ ForgotPasswordScreen: () => null }));
jest.mock('../screens/VerifyPhoneScreen', () => ({ VerifyPhoneScreen: () => null }));

const mockedUseAuth = useAuth as jest.MockedFunction<typeof useAuth>;
const mockedUseOnboardingStep = useOnboardingStep as jest.MockedFunction<typeof useOnboardingStep>;

function authState(overrides: Partial<ReturnType<typeof useAuth>> = {}): ReturnType<typeof useAuth> {
  return {
    bootstrapping: false,
    token: null,
    email: null,
    fullName: null,
    phoneVerified: false,
    onboardingCompleted: false,
    login: jest.fn(),
    reactivate: jest.fn(),
    register: jest.fn(),
    loginWithGoogle: jest.fn(),
    loginWithApple: jest.fn(),
    setPhoneVerified: jest.fn(),
    setOnboardingCompleted: jest.fn(),
    logout: jest.fn(),
    ...overrides,
  } as ReturnType<typeof useAuth>;
}

describe('RootNavigator', () => {
  beforeEach(() => {
    mockedUseOnboardingStep.mockReturnValue({ step: 'welcome', setStep: jest.fn() });
  });

  it('mounts AppTabs when signed in, verified, and onboarded', () => {
    mockedUseAuth.mockReturnValue(authState({ token: 'tok', phoneVerified: true, onboardingCompleted: true }));

    render(<RootNavigator />);

    expect(screen.getByTestId('app-tabs')).toBeTruthy();
    expect(screen.queryByTestId('tour-overlay')).toBeNull();
  });

  it('mounts OnboardingNavigator when signed in, verified, but onboarding is not complete and step is not tour', () => {
    mockedUseAuth.mockReturnValue(authState({ token: 'tok', phoneVerified: true, onboardingCompleted: false }));

    render(<RootNavigator />);

    expect(screen.getByTestId('onboarding-navigator')).toBeTruthy();
    expect(screen.queryByTestId('app-tabs')).toBeNull();
  });

  it('mounts the REAL AppTabs plus TourOverlay when the onboarding step is tour', () => {
    mockedUseAuth.mockReturnValue(authState({ token: 'tok', phoneVerified: true, onboardingCompleted: false }));
    mockedUseOnboardingStep.mockReturnValue({ step: 'tour', setStep: jest.fn() });

    render(<RootNavigator />);

    expect(screen.getByTestId('app-tabs')).toBeTruthy();
    expect(screen.getByTestId('tour-overlay')).toBeTruthy();
    expect(screen.queryByTestId('onboarding-navigator')).toBeNull();
  });

  it('still routes to VerifyPhone when unverified, before onboarding is ever considered', () => {
    mockedUseAuth.mockReturnValue(authState({ token: 'tok', phoneVerified: false, onboardingCompleted: false }));

    render(<RootNavigator />);

    expect(screen.queryByTestId('onboarding-navigator')).toBeNull();
    expect(screen.queryByTestId('app-tabs')).toBeNull();
  });
});
