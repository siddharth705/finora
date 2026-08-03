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

// Every test starts from a clean SecureStore so persistence assertions can't leak between them.
beforeEach(() => {
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  const secureStore = require('expo-secure-store');
  secureStore.__store.clear();
  jest.clearAllMocks();
});
