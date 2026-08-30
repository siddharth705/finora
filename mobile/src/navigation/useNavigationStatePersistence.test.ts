import { act, renderHook, waitFor } from '@testing-library/react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { clearPersistedNavigationState, useNavigationStatePersistence } from './useNavigationStatePersistence';

// Mocked globally in src/test/setup.ts, same posture as expo-secure-store -- an in-memory map with
// real async semantics, cleared before every test.
const NAV_STATE_KEY = 'finora_nav_state';

describe('useNavigationStatePersistence', () => {
  it('stays not-ready while bootstrapping, regardless of active', async () => {
    const { result } = renderHook(() => useNavigationStatePersistence(true, true));
    expect(result.current.isReady).toBe(false);
  });

  it('becomes ready without restoring anything when inactive (signed out / phone-unverified)', async () => {
    await AsyncStorage.setItem(NAV_STATE_KEY, JSON.stringify({ index: 0, routes: [{ name: 'Home' }] }));
    const { result } = renderHook(() => useNavigationStatePersistence(false, false));
    await waitFor(() => expect(result.current.isReady).toBe(true));
    expect(result.current.initialState).toBeUndefined();
  });

  it('restores previously saved state once active and not bootstrapping', async () => {
    const saved = { index: 1, routes: [{ name: 'Home' }, { name: 'Transactions' }] };
    await AsyncStorage.setItem(NAV_STATE_KEY, JSON.stringify(saved));
    const { result } = renderHook(() => useNavigationStatePersistence(false, true));
    await waitFor(() => expect(result.current.isReady).toBe(true));
    expect(result.current.initialState).toEqual(saved);
  });

  it('ignores corrupt persisted state instead of throwing, and still becomes ready', async () => {
    await AsyncStorage.setItem(NAV_STATE_KEY, 'not json');
    const { result } = renderHook(() => useNavigationStatePersistence(false, true));
    await waitFor(() => expect(result.current.isReady).toBe(true));
    expect(result.current.initialState).toBeUndefined();
  });

  it('onStateChange persists state while active, with params stripped from every route', async () => {
    const { result } = renderHook(() => useNavigationStatePersistence(false, true));
    await waitFor(() => expect(result.current.isReady).toBe(true));

    const stateWithParams = {
      index: 0,
      routes: [
        {
          name: 'More',
          state: {
            index: 0,
            routes: [
              { name: 'VerifyEmailChange', params: { sessionId: 's1', token: 'super-secret-token' } },
            ],
          },
        },
      ],
    };

    act(() => {
      result.current.onStateChange(stateWithParams as never);
    });

    await waitFor(async () => {
      const raw = await AsyncStorage.getItem(NAV_STATE_KEY);
      expect(raw).not.toBeNull();
    });

    const persisted = JSON.parse((await AsyncStorage.getItem(NAV_STATE_KEY))!);
    expect(persisted).toEqual({
      index: 0,
      routes: [{ name: 'More', state: { index: 0, routes: [{ name: 'VerifyEmailChange' }] } }],
    });
    expect(JSON.stringify(persisted)).not.toContain('super-secret-token');
  });

  it('onStateChange is a no-op while inactive', async () => {
    const { result } = renderHook(() => useNavigationStatePersistence(false, false));
    await waitFor(() => expect(result.current.isReady).toBe(true));

    act(() => {
      result.current.onStateChange({ index: 0, routes: [{ name: 'Login' }] } as never);
    });

    expect(await AsyncStorage.getItem(NAV_STATE_KEY)).toBeNull();
  });
});

describe('clearPersistedNavigationState', () => {
  it('removes the persisted key', async () => {
    await AsyncStorage.setItem(NAV_STATE_KEY, JSON.stringify({ index: 0, routes: [] }));
    await clearPersistedNavigationState();
    expect(await AsyncStorage.getItem(NAV_STATE_KEY)).toBeNull();
  });
});
