/**
 * Jest setup. Mocks the native modules that have no JS implementation under the test runner --
 * without these, importing anything that transitively reaches them throws before a test can run.
 *
 * This runs via `setupFilesAfterEnv`, deliberately NOT `setupFiles`. Jest's `setupFiles` REPLACES
 * the preset's own array rather than merging with it, and jest-expo's preset uses that slot for
 * React Native's environment setup. Overriding it silently removes that setup, after which
 * RNTL's `render` becomes a no-op that returns an empty object and every query fails with
 * "`render` function has not been called" -- an hour-long red herring, since nothing points at
 * the config. `setupFilesAfterEnv` still runs before the test file's own imports, which is all
 * the env assignment below needs.
 */

// src/api/client.ts throws at import time when this is missing -- intentional, since a native app
// has no dev-server proxy to fall back on. Tests supply a value; nothing contacts it, because
// everything that would touch the network mocks the endpoint layer.
process.env.EXPO_PUBLIC_API_BASE_URL = 'https://tests.invalid';

// Reanimated ships a real (non-native) implementation for use under Jest -- see
// https://docs.swmansion.com/react-native-reanimated/docs/guides/testing. AnimatedNumber
// (src/components/AnimatedNumber.tsx) and the chart reveal components in
// src/components/charts/ChartReveal.tsx both depend on this being called before any test that
// renders them. Not a jest.mock -- this is a real setup call against the actual test-mode
// Reanimated runtime, so it belongs before the native-module mocks below rather than among them.
require('react-native-reanimated').setUpTests();

// SecureStore is a native module; back it with a plain in-memory map so AuthContext's real
// persistence logic (and its async-ness, which is the whole reason mobile diverges from web here)
// is exercised rather than stubbed out.
jest.mock('expo-secure-store', () => {
  const store = new Map<string, string>();
  return {
    __store: store,
    getItemAsync: jest.fn(async (k: string) => (store.has(k) ? store.get(k)! : null)),
    setItemAsync: jest.fn(async (k: string, v: string) => {
      store.set(k, v);
    }),
    deleteItemAsync: jest.fn(async (k: string) => {
      store.delete(k);
    }),
  };
});

// AsyncStorage is a native module too. Same posture as SecureStore just above -- a plain in-memory
// map, real async semantics, so useNavigationStatePersistence's actual persistence logic is
// exercised rather than stubbed out.
jest.mock('@react-native-async-storage/async-storage', () => {
  const store = new Map<string, string>();
  return {
    __esModule: true,
    __store: store,
    default: {
      getItem: jest.fn(async (k: string) => (store.has(k) ? store.get(k)! : null)),
      setItem: jest.fn(async (k: string, v: string) => {
        store.set(k, v);
      }),
      removeItem: jest.fn(async (k: string) => {
        store.delete(k);
      }),
      clear: jest.fn(async () => {
        store.clear();
      }),
    },
  };
});

// Firebase Auth needs a real native app registered; nothing under test drives it directly.
jest.mock('@react-native-firebase/auth', () => ({
  getAuth: jest.fn(() => ({})),
  signInWithPhoneNumber: jest.fn(),
  signOut: jest.fn(async () => {}),
}));

jest.mock('@react-native-community/netinfo', () => ({
  __esModule: true,
  default: { addEventListener: jest.fn(() => jest.fn()) },
}));

// react-navigation's useFocusEffect needs a real Navigator/Screen context, which most screen unit
// tests here render without (see DashboardScreen.test.tsx, which mounts DashboardScreen bare, the
// same way OfflineBanner.test.tsx mocks RootNavigator away rather than building a real navigation
// tree). Faked as a plain mount effect so Dashboard's prefetch-on-focus wiring can be exercised
// without every affected screen test growing a navigation tree it otherwise has no use for.
//
// jest.requireActual is deliberately NOT used here (unlike most mocks in this file that spread the
// real module): @react-navigation/native's installed build ships ESM-syntax source at its "main"
// entry, which this project's Jest config cannot load directly once nothing intercepts resolution
// first -- every other test file in this repo that touches this package mocks it outright for the
// same reason (see StatementHistoryScreen.test.tsx). Everything this app actually imports from the
// package (NavigationContainer, useNavigation, etc.) is provided here as a lightweight stand-in.
jest.mock('@react-navigation/native', () => {
  const { useEffect } = require('react');
  // Built inside the factory, not above it: Jest rejects a mock factory that closes over an outer
  // variable unless its name is `mock`-prefixed, and this reads better than renaming it.
  const navigationStub: Record<string, unknown> = {
    navigate: jest.fn(),
    goBack: jest.fn(),
    setOptions: jest.fn(),
  };
  navigationStub.getParent = jest.fn(() => navigationStub);
  return {
    // `effect` is a passthrough argument from whatever hook calls useFocusEffect, not a value
    // this mock can statically analyze; the real useFocusEffect re-runs on every focus, so
    // running once per mount here (matching the app's own useCallback-memoized effects) is the
    // correct test-time behavior.
    useFocusEffect: (effect: () => void | (() => void)) => {
      // eslint-disable-next-line react-hooks/exhaustive-deps
      useEffect(effect, []);
    },
    // Screens that only NAVIGATE (rather than assert on navigation) need this to exist but do not
    // care what it does -- DashboardScreen's review-queue nudge and SettingsScreen's link into it
    // are both that case, and without a stand-in here every such screen's whole suite dies at
    // render with "useNavigation is not a function", which is what the comment above already
    // promised this mock would prevent.
    //
    // One frozen object rather than a fresh one per call: a new identity each render would make
    // `navigation` an unstable dependency for any useEffect/useMemo that closes over it. A test
    // that wants to ASSERT a navigation still declares its own file-level jest.mock of this
    // module, which overrides this one entirely (see StatementHistoryScreen.test.tsx).
    useNavigation: () => navigationStub,
  };
});

// @expo/vector-icons reaches expo-font -> expo-asset, which isn't resolvable under the runner, so
// importing any screen that shows an icon fails before a test can run. Rendered as a plain Text
// node carrying the glyph name: icons are decorative here, and every control this project ships
// carries its own accessibilityLabel, so nothing under test depends on the real glyph.
jest.mock('@expo/vector-icons/Ionicons', () => {
  const React = require('react');
  const { Text } = require('react-native');
  return {
    __esModule: true,
    // createElement, not Text({...}): calling a component as a plain function skips React's
    // element creation and blows up the tree on render rather than producing a node.
    default: ({ name }: { name: string }) => React.createElement(Text, null, name),
  };
});

// Safe-area insets come from a native module and are otherwise only available under a real
// <SafeAreaProvider>. Zeroed here so every screen test doesn't have to wrap its own provider --
// no assertion in this suite depends on the notch size.
//
// SafeAreaInsetsContext is exported too, because OfflineBoundary re-provides the top inset to its
// subtree (see its own comment on why double-counting the notch would shift every screen down the
// moment the banner appears). While it was missing, the real export read as undefined and anything
// rendering that boundary died on `Cannot read properties of undefined (reading 'Provider')` -- a
// property of this mock, not of the component, and the kind of failure that sends someone hunting
// through source that turns out to be correct.
jest.mock('react-native-safe-area-context', () => {
  const React = require('react');
  const insets = { top: 0, bottom: 0, left: 0, right: 0 };
  return {
    useSafeAreaInsets: () => insets,
    SafeAreaProvider: ({ children }: { children: unknown }) => children,
    SafeAreaInsetsContext: React.createContext(insets),
  };
});

// The system date picker is a native module. Rendered as nothing, with the imperative Android
// entry point stubbed: DateField's job under test is what it does with the value it gets back, and
// the OS dialog itself has no JS implementation to exercise.
jest.mock('@react-native-community/datetimepicker', () => ({
  __esModule: true,
  default: () => null,
  DateTimePickerAndroid: { open: jest.fn(), dismiss: jest.fn() },
}));

// D-23 Phase 2. Native module -- rendered as a plain Pressable/Text so GoogleSignInButton's own
// onPress wiring is exercised for real; signIn()/hasPlayServices() are left as bare jest.fn()s for
// each test to configure, same posture as authApi's own mock in AuthContext.test.tsx.
jest.mock('@react-native-google-signin/google-signin', () => {
  const React = require('react');
  const { Pressable, Text } = require('react-native');
  const GoogleSigninButton = ({
    onPress,
    disabled,
  }: {
    onPress: () => void;
    disabled?: boolean;
  }) =>
    React.createElement(
      Pressable,
      { onPress, disabled, accessibilityRole: 'button', accessibilityLabel: 'Sign in with Google' },
      React.createElement(Text, null, 'Sign in with Google')
    );
  GoogleSigninButton.Size = { Icon: 0, Standard: 1, Wide: 2 };
  GoogleSigninButton.Color = { Dark: 'dark', Light: 'light' };
  return {
    __esModule: true,
    GoogleSignin: {
      configure: jest.fn(),
      hasPlayServices: jest.fn(async () => true),
      signIn: jest.fn(),
      signOut: jest.fn(async () => {}),
    },
    GoogleSigninButton,
    isSuccessResponse: (response: { type: string }) => response?.type === 'success',
    isErrorWithCode: (err: unknown): err is { code: string } =>
      typeof (err as { code?: unknown })?.code === 'string',
    statusCodes: {
      SIGN_IN_CANCELLED: 'SIGN_IN_CANCELLED',
      IN_PROGRESS: 'IN_PROGRESS',
      PLAY_SERVICES_NOT_AVAILABLE: 'PLAY_SERVICES_NOT_AVAILABLE',
    },
  };
});

// D-26. Native module, same posture as the Google mock above -- a plain Pressable/Text standing
// in for the real branded button, signInAsync() left for each test to configure.
jest.mock('expo-apple-authentication', () => {
  const React = require('react');
  const { Pressable, Text } = require('react-native');
  return {
    __esModule: true,
    isAvailableAsync: jest.fn(async () => true),
    signInAsync: jest.fn(),
    formatFullName: jest.fn((name: { givenName?: string; familyName?: string }) =>
      [name.givenName, name.familyName].filter(Boolean).join(' ')
    ),
    AppleAuthenticationButton: ({ onPress }: { onPress: () => void }) =>
      React.createElement(
        Pressable,
        { onPress, accessibilityRole: 'button', accessibilityLabel: 'Sign in with Apple' },
        React.createElement(Text, null, 'Sign in with Apple')
      ),
    AppleAuthenticationButtonType: { SIGN_IN: 0, CONTINUE: 1, SIGN_UP: 2 },
    AppleAuthenticationButtonStyle: { WHITE: 0, WHITE_OUTLINE: 1, BLACK: 2 },
    AppleAuthenticationScope: { FULL_NAME: 0, EMAIL: 1 },
  };
});

// Sentry's native module isn't present under the runner. The scrubbers in lib/monitoring.ts are
// pure functions tested directly, so nothing here needs the real SDK -- and EXPO_PUBLIC_SENTRY_DSN
// is deliberately left unset so initMonitoring() no-ops and no test can emit a real event.
jest.mock('@sentry/react-native', () => ({
  init: jest.fn(),
  wrap: jest.fn((component: unknown) => component),
  captureException: jest.fn(),
}));

// SEC-08. Has no native module under the runner; without a mock, `isRootedExperimentalAsync`
// falls through to a real (non-native) code path that still resolves asynchronously, producing
// act()-wrapping warnings in any test that mounts RootWarningBoundary without awaiting it.
// Defaults to "not rooted" -- each test overrides with mockResolvedValueOnce for the flagged case.
jest.mock('expo-device', () => ({
  isRootedExperimentalAsync: jest.fn(async () => false),
}));

// SEC-09. Defaults model an unenrolled device (supported hardware, nothing enrolled) so
// isSupported() is false unless a test opts in -- the safer default for a control this codebase
// treats as opt-in. authenticateAsync defaults to a cancelled/failed result for the same reason.
jest.mock('expo-local-authentication', () => ({
  hasHardwareAsync: jest.fn(async () => true),
  isEnrolledAsync: jest.fn(async () => false),
  authenticateAsync: jest.fn(async () => ({ success: false })),
}));

// SEC-17. No native module under the runner; this app only ever calls the hook form, so nothing
// under test needs it to actually do anything.
jest.mock('expo-screen-capture', () => ({
  usePreventScreenCapture: jest.fn(),
}));

// expo-haptics is a native module with no JS implementation under the test runner -- same
// posture as expo-screen-capture and expo-device above. Every haptic touchpoint in the app calls
// through src/lib/haptics.ts, so stubbing the underlying three Expo APIs here is enough for both
// haptics.test.ts and any screen test asserting a particular haptic fired.
jest.mock('expo-haptics', () => ({
  notificationAsync: jest.fn(async () => {}),
  impactAsync: jest.fn(async () => {}),
  selectionAsync: jest.fn(async () => {}),
  NotificationFeedbackType: { Success: 'success', Warning: 'warning', Error: 'error' },
  ImpactFeedbackStyle: { Light: 'light', Medium: 'medium', Heavy: 'heavy', Rigid: 'rigid', Soft: 'soft' },
}));

// Every test starts from a clean SecureStore/AsyncStorage so persistence assertions can't leak
// between them.
beforeEach(() => {
  // require(), not import: the mock has to be read AFTER jest.mock above has replaced the module,
  // and a static import would be hoisted above it. Allowed for test files in eslint.config.js.
  const secureStore = require('expo-secure-store');
  secureStore.__store.clear();
  const asyncStorage = require('@react-native-async-storage/async-storage');
  asyncStorage.__store.clear();
  jest.clearAllMocks();
});
