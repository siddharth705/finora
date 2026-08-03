import NetInfo from '@react-native-community/netinfo';
import { QueryClient, onlineManager } from '@tanstack/react-query';

/**
 * Mirrors the web app's QueryClient config (frontend/src/App.tsx). refetchOnWindowFocus is a
 * browser concept with no native equivalent, and the web app explicitly disables it anyway, so
 * it's simply omitted rather than re-implemented against AppState -- that would enable a behavior
 * the web app deliberately turned off.
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      staleTime: 30_000,
    },
  },
});

/**
 * Teaches React Query what "online" means on a device.
 *
 * Without this it assumes online forever, which is the right default on the web (the browser has
 * its own online/offline events) but wrong on a phone. Two things were broken as a result:
 * `refetchOnReconnect` never fired, because there was no reconnect event to observe; and queries
 * issued with no connectivity would burn their retry immediately and settle as errors instead of
 * pausing until the network came back.
 *
 * `isInternetReachable` is deliberately preferred over `isConnected`, falling back to it only
 * while the reachability probe is still pending (it's null at that point, not false). They differ
 * in exactly the case worth handling: attached to a wifi network that has no working route out --
 * a captive portal, a hotel network, a router with no upstream. `isConnected` is true there and
 * every request still fails.
 */
export function startNetworkMonitoring(): () => void {
  return NetInfo.addEventListener((state) => {
    onlineManager.setOnline(
      state.isInternetReachable ?? state.isConnected ?? true
    );
  });
}
