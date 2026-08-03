import { QueryClient } from '@tanstack/react-query';

/**
 * Mirrors the web app's QueryClient config (frontend/src/App.tsx). refetchOnWindowFocus is a
 * browser concept with no native equivalent, and the web app explicitly disables it anyway, so
 * it's simply omitted here rather than re-implemented against AppState -- that would enable a
 * behavior the web app deliberately turned off.
 *
 * refetchOnReconnect stays at its default. Making it actually reflect device connectivity needs
 * @react-native-community/netinfo feeding React Query's onlineManager -- see the mobile roadmap's
 * Phase 6 offline-handling item. Until then React Query assumes online, the same assumption the
 * web app makes today.
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      staleTime: 30_000,
    },
  },
});
